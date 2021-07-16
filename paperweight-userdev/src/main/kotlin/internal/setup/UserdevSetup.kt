/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.userdev.internal.setup

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkerExecutor

class UserdevSetup(
    private val project: Project,
    private val workerExecutor: WorkerExecutor,
    private val javaToolchainService: JavaToolchainService,
    private val cache: Path = project.layout.cache
) {
    companion object {
        private const val APPLY_MOJANG_MAPPED_PAPERCLIP = "applyMojangMappedPaperclipPatch"
        private const val DECOMPILE_MINECRAFT_SERVER_JAR = "decompileMinecraftServerJar"
        private const val MAPPED_MINECRAFT_SERVER_JAR = "mappedMinecraftServerJar"
    }

    val extractedBundle: Path = cache.resolve(paperConfigurationOutput("extractDevBundle", "dir"))
    private val extractDevBundle = extractDevBundle(
        extractedBundle,
        project.configurations.named(DEV_BUNDLE_CONFIG).map { it.singleFile }.convertToPath()
    )
    private val devBundleChanged = extractDevBundle.first
    val devBundleConfig = extractDevBundle.second

    private val minecraftVersionManifest: JsonObject by lazy {
        val minecraftManifestJson = download(
            "minecraft manifest",
            project.download,
            devBundleChanged,
            MC_MANIFEST_URL,
            cache.resolve(MC_MANIFEST)
        )
        val minecraftManifest = gson.fromJson<MinecraftManifest>(minecraftManifestJson)

        val minecraftVersionManifestJson = download(
            "minecraft version manifest",
            project.download,
            devBundleChanged,
            minecraftManifest.versions.first { it.id == devBundleConfig.minecraftVersion }.url,
            cache.resolve(VERSION_JSON)
        )

        gson.fromJson(minecraftVersionManifestJson)
    }

    private val vanillaServerJar: Path = cache.resolve(paperConfigurationOutput("downloadServerJar", "jar"))
    private fun downloadVanillaServerJar() {
        download(
            "vanilla minecraft server jar",
            project.download,
            devBundleChanged,
            minecraftVersionManifest["downloads"]["server"]["url"].string,
            vanillaServerJar
        )
    }

    private val filteredVanillaServerJar: Path = cache.resolve(paperConfigurationOutput("filterJar", "jar"))
    private fun filterVanillaServerJar() {
        downloadVanillaServerJar()
        val filteredJar = filteredVanillaServerJar

        if (!devBundleChanged && filteredJar.hasCorrect256()) {
            return
        }

        project.logger.lifecycle(":filtering vanilla server jar")
        filterJar(
            vanillaServerJar,
            filteredJar,
            devBundleConfig.buildData.vanillaJarIncludes
        )
        filteredJar.writeSha256()
    }

    private val mojangServerMappings: Path = cache.resolve(SERVER_MAPPINGS)
    private fun downloadMojangServerMappings() {
        download(
            "mojang server mappings",
            project.download,
            devBundleChanged,
            minecraftVersionManifest["downloads"]["server_mappings"]["url"].string,
            mojangServerMappings
        )
    }

    private val minecraftLibrariesFile: Path = cache.resolve(MC_LIBRARIES)
    private fun writeMinecraftLibrariesFile() {
        setupMinecraftLibraries(
            minecraftVersionManifest["libraries"].array.map { lib ->
                lib["name"].string
            },
            minecraftLibrariesFile
        )
    }

    private fun hashFiles(files: List<Path>): String =
        files.sortedBy { it.name }.joinToString("\n") {
            "${it.name}:${toHex(it.hashFile(digestSha256()))}"
        }

    private fun hashLibraries(jars: Path, sources: Path): String =
        hashFiles(
            sequenceOf(jars, sources)
                .filter { it.isDirectory() }
                .flatMap { it.listDirectoryEntries("*.jar") }
                .toList()
        )

    private val minecraftLibraryJars = cache.resolve(MINECRAFT_JARS_PATH)
    private val minecraftLibrarySources = cache.resolve(MINECRAFT_SOURCES_PATH)
    private fun downloadMinecraftLibraries() {
        writeMinecraftLibrariesFile()
        val jars = minecraftLibraryJars
        val sources = minecraftLibrarySources

        val hashesFile = cache.resolve(paperConfigurationOutput("libraries", "hashes"))
        val upToDate = !devBundleChanged &&
            hashesFile.isRegularFile() &&
            hashesFile.readText(Charsets.UTF_8) == hashLibraries(jars, sources)
        if (upToDate) {
            return
        }

        project.logger.lifecycle(":downloading minecraft libraries")
        downloadMinecraftLibraries(
            download = project.download,
            workerExecutor = workerExecutor,
            out = jars,
            sourcesOut = null, // sources, // we don't use sources jars for anything in userdev currently
            mcRepo = MC_LIBRARY_URL,
            mcLibrariesFile = minecraftLibrariesFile
        )
        workerExecutor.await()
        hashesFile.parent.createDirectories()
        hashesFile.writeText(hashLibraries(jars, sources))
    }

    private val mojangPlusYarnMappings: Path = cache.resolve(MOJANG_YARN_MAPPINGS)
    private fun generateMappings() {
        downloadMinecraftLibraries()
        downloadMojangServerMappings()
        filterVanillaServerJar()

        val mappingsFile = mojangPlusYarnMappings

        if (!devBundleChanged && mappingsFile.hasCorrect256()) {
            return
        }

        project.logger.lifecycle(":generating mappings")
        generateMappings(
            vanillaJarPath = filteredVanillaServerJar,
            libraryPaths = minecraftLibraryJars.listDirectoryEntries("*.jar"),
            vanillaMappingsPath = mojangServerMappings,
            paramMappingsPath = project.configurations.named(PARAM_MAPPINGS_CONFIG).map { it.singleFile }.convertToPath(),
            outputMappingsPath = mappingsFile,
            workerExecutor = workerExecutor,
            launcher = javaToolchainService.defaultJavaLauncher(project).get()
        )
        workerExecutor.await()
        mappingsFile.writeSha256()
    }

    private val mappedMinecraftServerJar: Path = cache.resolve(paperConfigurationOutput(MAPPED_MINECRAFT_SERVER_JAR, "jar"))
    private fun remapMinecraftServerJar() {
        generateMappings()

        val output = mappedMinecraftServerJar
        val logFile = cache.resolve(paperConfigurationOutput(MAPPED_MINECRAFT_SERVER_JAR, "log"))

        if (!devBundleChanged && output.hasCorrect256()) {
            return
        }

        project.logger.lifecycle(":remapping minecraft server jar")
        runTinyRemapper(
            argsList = devBundleConfig.remap.args,
            logFile = logFile,
            inputJar = filteredVanillaServerJar,
            mappingsFile = mojangPlusYarnMappings,
            fromNamespace = OBF_NAMESPACE,
            toNamespace = DEOBF_NAMESPACE,
            remapClasspath = minecraftLibraryJars.listDirectoryEntries("*.jar"),
            remapper = project.configurations.named(REMAPPER_CONFIG).get(),
            outputJar = output,
            launcher = javaToolchainService.defaultJavaLauncher(project).get(),
            workingDir = cache
        )
        output.writeSha256()
    }

    private val decompiledMinecraftServerJar: Path = cache.resolve(paperConfigurationOutput(DECOMPILE_MINECRAFT_SERVER_JAR, "jar"))
    private fun decompileMinecraftServerJar() {
        remapMinecraftServerJar()

        val output = decompiledMinecraftServerJar
        val logFile = cache.resolve(paperConfigurationOutput(DECOMPILE_MINECRAFT_SERVER_JAR, "log"))

        if (!devBundleChanged && output.hasCorrect256()) {
            return
        }

        project.logger.lifecycle(":decompiling mapped minecraft server jar")
        runForgeFlower(
            argsList = devBundleConfig.decompile.args,
            logFile = logFile,
            workingDir = cache,
            executable = project.configurations.named(DECOMPILER_CONFIG).get(),
            inputJar = mappedMinecraftServerJar,
            libraries = minecraftLibraryJars.listDirectoryEntries("*.jar"),
            outputJar = output,
            javaLauncher = javaToolchainService.defaultJavaLauncher(project).get()
        )
        output.writeSha256()
    }

    private val patchedSourcesJar: Path = cache.resolve(paperConfigurationOutput("patchedSourcesJar", "jar"))
    private fun patchDecompiledSources() {
        decompileMinecraftServerJar()

        val output = patchedSourcesJar

        if (!devBundleChanged && output.hasCorrect256()) {
            return
        }

        project.logger.lifecycle(":applying patches to decompiled jar")
        applyDevBundlePatches(
            decompiledMinecraftServerJar,
            extractedBundle.resolve(devBundleConfig.patchDir),
            output
        )
        output.writeSha256()
    }

    val mojangMappedPaperJar: Path = cache.resolve(paperConfigurationOutput(APPLY_MOJANG_MAPPED_PAPERCLIP, "jar"))
    private fun applyMojangMappedPaperclipPatch() {
        val output = mojangMappedPaperJar
        val logFile = cache.resolve(paperConfigurationOutput(APPLY_MOJANG_MAPPED_PAPERCLIP, "log"))

        if (!devBundleChanged && output.hasCorrect256()) {
            return
        }

        project.logger.lifecycle(":applying mojang mapped paperclip patch")
        patchPaperclip(
            project = project,
            launcher = javaToolchainService.defaultJavaLauncher(project).get(),
            paperclip = extractedBundle.resolve(devBundleConfig.buildData.mojangMappedPaperclipFile),
            outputJar = output,
            logFile = logFile
        )
        output.writeSha256()
    }

    private val filteredMojangMappedPaperJar: Path = cache.resolve(paperConfigurationOutput("filteredMojangMappedPaperJar", "jar"))
    private fun filterMojangMappedPaperJar() {
        applyMojangMappedPaperclipPatch()
        patchDecompiledSources()
        val input = mojangMappedPaperJar
        val sources = patchedSourcesJar
        val output = filteredMojangMappedPaperJar

        if (!devBundleChanged && output.hasCorrect256()) {
            return
        }

        println(":filtering mojang mapped paper jar")
        filterPaperJar(sources, input, output, devBundleConfig.buildData.relocations)
        output.writeSha256()
    }

    fun installServerArtifactToIvyRepository(repo: Path) {
        filterMojangMappedPaperJar()

        val hashes = cache.resolve(paperConfigurationOutput("installedArtifacts", "hashes"))

        val upToDate = hashes.isRegularFile() &&
            hashes.readText(Charsets.UTF_8) == hashFiles(listOf(patchedSourcesJar, filteredMojangMappedPaperJar))
        if (upToDate) {
            return
        }

        println(":installing server artifacts to cache")
        installToIvyRepo(
            repo,
            devBundleConfig.mappedServerCoordinates,
            patchedSourcesJar,
            filteredMojangMappedPaperJar
        )

        hashes.writeText(hashFiles(listOf(patchedSourcesJar, filteredMojangMappedPaperJar)), Charsets.UTF_8)

        println(":done setting up paperweight userdev workspace")
    }
}
