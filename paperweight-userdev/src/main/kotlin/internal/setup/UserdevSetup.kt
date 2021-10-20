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

import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

abstract class UserdevSetup : BuildService<UserdevSetup.Parameters> {

    companion object {
        private val LOGGER: Logger = Logging.getLogger(UserdevSetup::class.java)
    }

    data class Context(
        val project: Project,
        val workerExecutor: WorkerExecutor,
        val javaToolchainService: JavaToolchainService
    ) {
        val defaultJavaLauncher: JavaLauncher
            get() = javaToolchainService.defaultJavaLauncher(project).get()
    }

    interface Parameters : BuildServiceParameters {
        val bundleZip: RegularFileProperty
        val cache: RegularFileProperty
    }

    private val cache: Path
        get() = parameters.cache.path

    val extractedBundle: Path = cache.resolve(paperSetupOutput("extractDevBundle", "dir"))
    private val extractDevBundle = extractDevBundle(
        extractedBundle,
        parameters.bundleZip.path
    )
    private val devBundleChanged = extractDevBundle.first
    val devBundleConfig = extractDevBundle.second

    private lateinit var minecraftVersionManifest: JsonObject
    private fun initMinecraftVersionManifest(context: Context) {
        val minecraftManifestJson = context.project.downloadFileNow(
            MC_MANIFEST_URL,
            MINECRAFT_MANIFEST
        )
        val minecraftManifest = gson.fromJson<MinecraftManifest>(minecraftManifestJson)

        val minecraftVersionManifestJson = context.project.downloadFileNow(
            minecraftManifest.versions.first { it.id == devBundleConfig.minecraftVersion }.url,
            MINECRAFT_VERSION_MANIFEST
        )
        minecraftVersionManifest = gson.fromJson(minecraftVersionManifestJson)
    }

    private lateinit var vanillaServerJar: Path
    private fun downloadVanillaServerJar(context: Context) {
        initMinecraftVersionManifest(context)
        vanillaServerJar = context.project.downloadFileNow(
            minecraftVersionManifest["downloads"]["server"]["url"].string,
            VANILLA_SERVER_JAR
        )
    }

    private val filteredVanillaServerJar: Path = cache.resolve(paperSetupOutput("filterJar", "jar"))
    private fun filterVanillaServerJar(context: Context) {
        downloadVanillaServerJar(context)
        val filteredJar = filteredVanillaServerJar

        val hashFile = filteredJar.resolveSibling(filteredJar.nameWithoutExtension + ".hashes")

        val hash = { hash(vanillaServerJar, filteredVanillaServerJar, devBundleConfig.buildData.vanillaJarIncludes) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":filtering vanilla server jar")
        filterJar(
            vanillaServerJar,
            filteredJar,
            devBundleConfig.buildData.vanillaJarIncludes
        )
        hashFile.writeText(hash())
    }

    private lateinit var mojangServerMappings: Path
    private fun downloadMojangServerMappings(context: Context) {
        initMinecraftVersionManifest(context)
        mojangServerMappings = context.project.downloadFileNow(
            minecraftVersionManifest["downloads"]["server_mappings"]["url"].string,
            MOJANG_SERVER_MAPPINGS
        )
    }

    private lateinit var minecraftLibraryJars: List<Path>
    private fun downloadMinecraftLibraries(context: Context) {
        minecraftLibraryJars = context.project
            .downloadMinecraftLibraries(devBundleConfig.buildData.vanillaServerLibraries)
            .map { it.toPath() }
    }

    private val mojangPlusYarnMappings: Path = cache.resolve(MOJANG_YARN_MAPPINGS)
    private fun generateMappings(context: Context) {
        downloadMinecraftLibraries(context)
        downloadMojangServerMappings(context)
        filterVanillaServerJar(context)

        val mappingsFile = mojangPlusYarnMappings

        val hashFile = cache.resolve(paperSetupOutput("generateMappings", "hashes"))
        val hash = { hash(minecraftLibraryJars, mojangServerMappings, filteredVanillaServerJar) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":generating mappings")
        generateMappings(
            vanillaJarPath = filteredVanillaServerJar,
            libraryPaths = minecraftLibraryJars,
            vanillaMappingsPath = mojangServerMappings,
            paramMappingsPath = context.project.configurations.named(PARAM_MAPPINGS_CONFIG).map { it.singleFile }.convertToPath(),
            outputMappingsPath = mappingsFile,
            workerExecutor = context.workerExecutor,
            launcher = context.defaultJavaLauncher
        ).await()
        hashFile.parent.createDirectories()
        hashFile.writeText(hash())
    }

    private val mappedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("mappedMinecraftServerJar", "jar"))
    private fun remapMinecraftServerJar(context: Context) {
        generateMappings(context)

        val output = mappedMinecraftServerJar
        val logFile = output.resolveSibling(output.nameWithoutExtension + ".log")
        val hashFile = output.resolveSibling(output.nameWithoutExtension + ".hashes")

        val hash = {
            hash(
                minecraftLibraryJars,
                mojangPlusYarnMappings,
                filteredVanillaServerJar,
                devBundleConfig.remap.args,
                output
            )
        }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":remapping minecraft server jar")
        runTinyRemapper(
            argsList = devBundleConfig.remap.args,
            logFile = logFile,
            inputJar = filteredVanillaServerJar,
            mappingsFile = mojangPlusYarnMappings,
            fromNamespace = OBF_NAMESPACE,
            toNamespace = DEOBF_NAMESPACE,
            remapClasspath = minecraftLibraryJars,
            remapper = context.project.configurations.named(REMAPPER_CONFIG).get(),
            outputJar = output,
            launcher = context.defaultJavaLauncher,
            workingDir = cache
        )
        hashFile.parent.createDirectories()
        hashFile.writeText(hash())
    }

    private val fixedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("fixedMinecraftServerJar", "jar"))
    private fun fixMinecraftServerJar(context: Context) {
        remapMinecraftServerJar(context)

        val hashFile = fixedMinecraftServerJar.resolveSibling(fixedMinecraftServerJar.nameWithoutExtension + ".hashes")
        val hash = { hash(vanillaServerJar, mappedMinecraftServerJar, fixedMinecraftServerJar) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":fixing minecraft server jar")
        fixJar(
            workerExecutor = context.workerExecutor,
            launcher = context.defaultJavaLauncher,
            vanillaJarPath = vanillaServerJar,
            inputJarPath = mappedMinecraftServerJar,
            outputJarPath = fixedMinecraftServerJar
        ).await()
        hashFile.writeText(hash())
    }

    private val accessTransformedServerJar: Path = cache.resolve(paperSetupOutput("accessTransformedServerJar", "jar"))
    private fun accessTransformMinecraftServerJar(context: Context) {
        fixMinecraftServerJar(context)

        val at = extractedBundle.resolve(devBundleConfig.buildData.accessTransformFile)

        val hashFile = accessTransformedServerJar.resolveSibling(accessTransformedServerJar.nameWithoutExtension + ".hashes")
        val hash = { hash(fixedMinecraftServerJar, at, accessTransformedServerJar) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":access transforming minecraft server jar")
        applyAccessTransform(
            inputJarPath = fixedMinecraftServerJar,
            outputJarPath = accessTransformedServerJar,
            atFilePath = at,
            workerExecutor = context.workerExecutor,
            launcher = context.defaultJavaLauncher
        ).await()
        hashFile.writeText(hash())
    }

    private val decompiledMinecraftServerJar: Path = cache.resolve(paperSetupOutput("decompileMinecraftServerJar", "jar"))
    private fun decompileMinecraftServerJar(context: Context) {
        accessTransformMinecraftServerJar(context)

        val output = decompiledMinecraftServerJar
        val logFile = output.resolveSibling(output.nameWithoutExtension + ".log")
        val hashFile = output.resolveSibling(output.nameWithoutExtension + ".hashes")

        val hash = {
            hash(
                minecraftLibraryJars,
                accessTransformedServerJar,
                devBundleConfig.decompile.args,
                output
            )
        }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":decompiling transformed minecraft server jar")
        runForgeFlower(
            argsList = devBundleConfig.decompile.args,
            logFile = logFile,
            workingDir = cache,
            executable = context.project.configurations.named(DECOMPILER_CONFIG).get(),
            inputJar = accessTransformedServerJar,
            libraries = minecraftLibraryJars,
            outputJar = output,
            javaLauncher = context.defaultJavaLauncher
        )
        hashFile.writeText(hash())
    }

    private val patchedSourcesJar: Path = cache.resolve(paperSetupOutput("patchedSourcesJar", "jar"))
    private fun patchDecompiledSources(context: Context) {
        decompileMinecraftServerJar(context)

        val patches = extractedBundle.resolve(devBundleConfig.patchDir)
        val output = patchedSourcesJar
        val hashFile = output.resolveSibling(output.nameWithoutExtension + ".hashes")

        val hash = { hash(output, decompiledMinecraftServerJar, hashDirectory(patches)) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":applying patches to decompiled jar")
        applyDevBundlePatches(
            decompiledMinecraftServerJar,
            patches,
            output
        )
        hashFile.parent.createDirectories()
        hashFile.writeText(hash())
    }

    val mojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("applyMojangMappedPaperclipPatch", "jar"))

    // This can be called when a user queries the server jar provider in
    // PaperweightUserExtension, possibly by a task running in a separate
    // thread to dependency resolution.
    @Synchronized
    fun applyMojangMappedPaperclipPatch(context: Context) {
        val paperclip = extractedBundle.resolve(devBundleConfig.buildData.mojangMappedPaperclipFile)
        val output = mojangMappedPaperJar
        val logFile = output.resolveSibling(output.nameWithoutExtension + ".log")
        val hashFile = output.resolveSibling(output.nameWithoutExtension + ".hashes")

        val hash = { hash(paperclip, mojangMappedPaperJar) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":applying mojang mapped paperclip patch")
        patchPaperclip(
            project = context.project,
            launcher = context.defaultJavaLauncher,
            paperclip = paperclip,
            outputJar = output,
            logFile = logFile
        )
        hashFile.parent.createDirectories()
        hashFile.writeText(hash())
    }

    private val filteredMojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("filteredMojangMappedPaperJar", "jar"))
    private fun filterMojangMappedPaperJar(context: Context) {
        patchDecompiledSources(context)
        applyMojangMappedPaperclipPatch(context)
        val input = mojangMappedPaperJar
        val sources = patchedSourcesJar
        val output = filteredMojangMappedPaperJar

        val hashFile = output.resolveSibling(output.nameWithoutExtension + ".hashes")
        val hash = { hash(input, sources, output, gson.toJson(devBundleConfig.buildData.relocations)) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":filtering mojang mapped paper jar")
        filterPaperJar(sources, input, output, devBundleConfig.buildData.relocations)
        hashFile.parent.createDirectories()
        hashFile.writeText(hash())
    }

    private var setupCompleted = false

    @Synchronized
    fun createOrUpdateIvyRepository(context: Context) {
        if (setupCompleted) {
            return
        }

        filterMojangMappedPaperJar(context)

        val didInstall = installToIvyRepo(
            cache.resolve(IVY_REPOSITORY),
            devBundleConfig.mappedServerCoordinates,
            devBundleConfig.buildData.libraryDependencies.toList() +
                devBundleConfig.apiCoordinates +
                devBundleConfig.mojangApiCoordinates,
            patchedSourcesJar,
            filteredMojangMappedPaperJar
        )
        if (didInstall) {
            LOGGER.lifecycle(":installed server artifacts to cache")
            LOGGER.lifecycle(":done setting up paperweight userdev workspace")
        }

        setupCompleted = true
    }

    fun addIvyRepository(project: Project) {
        project.repositories {
            setupIvyRepository(cache.resolve(IVY_REPOSITORY)) {
                content { includeFromDependencyNotation(devBundleConfig.mappedServerCoordinates) }
            }
        }
    }

    private fun hash(vararg things: Any): String = hash(things.toList())

    private fun hash(things: List<Any>): String {
        val strings = arrayListOf<String>()
        val paths = arrayListOf<Path>()

        for (thing in things) {
            when (thing) {
                is String -> strings.add(thing)
                is Path -> paths.add(thing)
                is Iterable<*> -> strings.add(hash(thing.filterNotNull()))
                else -> error("Unknown type: ${thing.javaClass.name}")
            }
        }

        return hashFiles(paths) + if (strings.isEmpty()) {
            ""
        } else {
            "\n" + strings.sorted().joinToString("\n") { toHex(it.byteInputStream().hash(digestSha256())) }
        }
    }

    private fun hashFiles(files: List<Path>): String = files
        .filter { it.isRegularFile() }
        .sortedBy { it.pathString }
        .joinToString("\n") {
            "${it.name}:${toHex(it.hashFile(digestSha256()))}"
        }

    private fun hashDirectory(dir: Path): String =
        Files.walk(dir).use { stream -> hashFiles(stream.filter { it.isRegularFile() }.collect(Collectors.toList())) }
}
