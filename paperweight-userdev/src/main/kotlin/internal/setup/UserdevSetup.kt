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
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup.HashFunction
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
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
        val downloadService: Property<DownloadService>
    }

    private val cache: Path
        get() = parameters.cache.path

    private val paperweightHash: String = hashPaperweightJar()

    val extractedBundle: Path = cache.resolve(paperSetupOutput("extractDevBundle", "dir"))
    private val extractDevBundle = extractDevBundle(
        extractedBundle,
        parameters.bundleZip.path
    )
    private val devBundleChanged = extractDevBundle.first
    val devBundleConfig = extractDevBundle.second

    private fun downloadMinecraftManifest(force: Boolean): DownloadResult<MinecraftManifest> =
        download("minecraft manifest", MC_MANIFEST_URL, cache.resolve(MC_MANIFEST), force)
            .mapData { gson.fromJson(it.path) }

    private val minecraftVersionManifest: JsonObject by lazy { setupMinecraftVersionManifest() }
    private fun setupMinecraftVersionManifest(): JsonObject {
        var minecraftManifest = downloadMinecraftManifest(devBundleChanged)
        if (!minecraftManifest.didDownload && minecraftManifest.data.versions.none { it.id == devBundleConfig.minecraftVersion }) {
            minecraftManifest = downloadMinecraftManifest(true)
        }

        val minecraftVersionManifestJson = download(
            "minecraft version manifest",
            minecraftManifest.data.versions.firstOrNull { it.id == devBundleConfig.minecraftVersion }?.url
                ?: throw PaperweightException("Could not find Minecraft version '${devBundleConfig.minecraftVersion}' in the downloaded manifest."),
            cache.resolve(VERSION_JSON)
        )
        return gson.fromJson(minecraftVersionManifestJson.path)
    }

    private val serverBundlerJar: Path = cache.resolve(paperSetupOutput("downloadServerJar", "jar"))
    private fun downloadVanillaServerJar() {
        download(
            "vanilla minecraft server jar",
            minecraftVersionManifest["downloads"]["server"]["url"].string,
            serverBundlerJar
        )
    }

    private val vanillaServerJar: Path = cache.resolve(paperSetupOutput("vanillaServerJar", "jar"))
    private val minecraftLibraryJars = cache.resolve(MINECRAFT_JARS_PATH)
    private fun extractFromServerBundler() {
        downloadVanillaServerJar()

        val hashFunction = buildHashFunction(serverBundlerJar, vanillaServerJar) {
            add(minecraftLibraryJars.filesMatchingRecursive("*.jar"))
        }
        val hashFile = cache.resolve(paperSetupOutput("extractFromServerBundler", "hashes"))
        if (hashFunction.upToDate(hashFile)) {
            return
        }

        LOGGER.lifecycle(":extracting libraries and server from downloaded jar")
        ServerBundler.extractFromBundler(
            serverBundlerJar,
            vanillaServerJar,
            minecraftLibraryJars,
            null,
            null,
            null,
            null,
        )
        hashFunction.writeHash(hashFile)
    }

    private val filteredVanillaServerJar: Path = cache.resolve(paperSetupOutput("filterJar", "jar"))
    private fun filterVanillaServerJar() {
        extractFromServerBundler()
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

    private val mojangServerMappings: Path = cache.resolve(SERVER_MAPPINGS)
    private fun downloadMojangServerMappings() {
        download(
            "mojang server mappings",
            minecraftVersionManifest["downloads"]["server_mappings"]["url"].string,
            mojangServerMappings
        )
    }

    private val mojangPlusYarnMappings: Path = cache.resolve(MOJANG_YARN_MAPPINGS)
    private fun generateMappings(context: Context) {
        extractFromServerBundler()
        downloadMojangServerMappings()
        filterVanillaServerJar()

        val mappingsFile = mojangPlusYarnMappings

        val hashFile = cache.resolve(paperSetupOutput("generateMappings", "hashes"))
        val hash = { hash(minecraftLibraryJars.filesMatchingRecursive("*.jar"), mojangServerMappings, filteredVanillaServerJar) }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":generating mappings")
        generateMappings(
            vanillaJarPath = filteredVanillaServerJar,
            libraryPaths = minecraftLibraryJars.filesMatchingRecursive("*.jar"),
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
                minecraftLibraryJars.filesMatchingRecursive("*.jar"),
                mojangPlusYarnMappings,
                filteredVanillaServerJar,
                devBundleConfig.buildData.minecraftRemapArgs,
                output
            )
        }
        val upToDate = hashFile.isRegularFile() && hashFile.readText() == hash()
        if (upToDate) return

        LOGGER.lifecycle(":remapping minecraft server jar")
        TinyRemapper.run(
            argsList = devBundleConfig.buildData.minecraftRemapArgs,
            logFile = logFile,
            inputJar = filteredVanillaServerJar,
            mappingsFile = mojangPlusYarnMappings,
            fromNamespace = OBF_NAMESPACE,
            toNamespace = DEOBF_NAMESPACE,
            remapClasspath = minecraftLibraryJars.filesMatchingRecursive("*.jar"),
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
                minecraftLibraryJars.filesMatchingRecursive("*.jar"),
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
            libraries = minecraftLibraryJars.filesMatchingRecursive("*.jar"),
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

    /*
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
     */

    private var setupCompleted = false

    @Synchronized
    fun createOrUpdateIvyRepository(context: Context) {
        if (setupCompleted) {
            return
        }

        patchDecompiledSources(context)
        applyMojangMappedPaperclipPatch(context)

        // filterMojangMappedPaperJar(context)

        val didInstall = installToIvyRepo(
            cache.resolve(IVY_REPOSITORY),
            devBundleConfig.mappedServerCoordinates,
            devBundleConfig.buildData.compileDependencies.toList() +
                devBundleConfig.apiCoordinates +
                devBundleConfig.mojangApiCoordinates,
            patchedSourcesJar,
            mojangMappedPaperJar
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
            "\n" + strings.sorted().joinToString("\n") { toHex(it.byteInputStream().hash(digestSha256)) }
        }
    }

    private fun hashFiles(files: List<Path>): String = files.asSequence()
        .filter { it.isRegularFile() }
        .sortedBy { it.pathString }
        .joinToString("\n") {
            "${it.fileName.pathString}:${it.sha256asHex()}"
        }

    private fun hashDirectory(dir: Path): String =
        Files.walk(dir).use { stream -> hashFiles(stream.filter { it.isRegularFile() }.collect(Collectors.toList())) }

    private fun download(
        downloadName: String,
        remote: String,
        destination: Path,
        forceDownload: Boolean = false,
    ): DownloadResult<Unit> {
        val hashFile = destination.resolveSibling(destination.name + ".hashes")

        val upToDate = !forceDownload &&
            hashFile.isRegularFile() &&
            hashFile.readText() == hash(remote, destination)
        if (upToDate) return DownloadResult(destination, false, Unit)

        LOGGER.lifecycle(":downloading $downloadName")
        destination.parent.createDirectories()
        parameters.downloadService.get().download(remote, destination)
        hashFile.writeText(hash(remote, destination))

        return DownloadResult(destination, true, Unit)
    }

    private fun buildHashFunction(
        vararg things: Any,
        op: HashFunctionBuilder.() -> Unit = {}
    ): HashFunction = HashFunction {
        val builder = HashFunctionBuilder.create()
        builder.op()
        if (builder.includePaperweightHash) {
            builder.add(paperweightHash)
        }
        builder.addAll(*things)

        hash(builder)
    }

    private data class DownloadResult<D>(val path: Path, val didDownload: Boolean, val data: D) {
        fun <N> mapData(mapper: (DownloadResult<D>) -> N): DownloadResult<N> =
            DownloadResult(path, didDownload, mapper(this))
    }

    private interface HashFunctionBuilder : MutableList<Any> {
        var includePaperweightHash: Boolean

        fun addAll(vararg things: Any): Boolean {
            return addAll(things.toList())
        }

        companion object {
            fun create(): HashFunctionBuilder = HashFunctionBuilderImpl()
        }

        private class HashFunctionBuilderImpl(
            override var includePaperweightHash: Boolean = true,
        ) : HashFunctionBuilder, MutableList<Any> by ArrayList()
    }

    private fun interface HashFunction : () -> String {
        fun writeHash(hashFile: Path) {
            hashFile.parent.createDirectories()
            hashFile.writeText(this())
        }

        fun upToDate(hashFile: Path): Boolean =
            hashFile.isRegularFile() && hashFile.readText() == this()
    }

    private fun hashPaperweightJar(): String {
        val userdevShadowJar = Paths.get(UserdevSetup::class.java.protectionDomain.codeSource.location.toURI())
        return hash(userdevShadowJar)
    }
}
