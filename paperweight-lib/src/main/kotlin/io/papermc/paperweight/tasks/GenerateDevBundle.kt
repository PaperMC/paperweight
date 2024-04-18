/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateDevBundle : DefaultTask() {

    @get:InputFile
    abstract val decompiledJar: RegularFileProperty

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    abstract val mojangMappedPaperclipFile: RegularFileProperty

    @get:Input
    abstract val serverVersion: Property<String>

    @get:Input
    abstract val serverCoordinates: Property<String>

    @get:Input
    abstract val apiCoordinates: Property<String>

    @get:Input
    abstract val mojangApiCoordinates: Property<String>

    @get:Input
    abstract val vanillaJarIncludes: ListProperty<String>

    @get:Input
    abstract val vanillaServerLibraries: ListProperty<String>

    @get:Input
    abstract val libraryRepositories: ListProperty<String>

    @get:Internal
    abstract val serverProject: Property<Project>

    @get:Classpath
    abstract val runtimeConfiguration: Property<Configuration>

    @get:Input
    abstract val paramMappingsUrl: Property<String>

    @get:Input
    abstract val paramMappingsCoordinates: Property<String>

    @get:Input
    abstract val decompilerUrl: Property<String>

    @get:Classpath
    abstract val decompilerConfig: Property<Configuration>

    @get:Input
    abstract val remapperUrl: Property<String>

    @get:Classpath
    abstract val remapperConfig: Property<Configuration>

    @get:InputFile
    abstract val reobfMappingsFile: RegularFileProperty

    @get:InputFile
    abstract val atFile: RegularFileProperty

    @get:OutputFile
    abstract val devBundleFile: RegularFileProperty

    @get:Inject
    abstract val layout: ProjectLayout

    @get:Input
    @get:Optional
    abstract val ignoreUnsupportedEnvironment: Property<Boolean>

    @TaskAction
    fun run() {
        checkEnvironment()

        val devBundle = devBundleFile.path
        devBundle.deleteForcefully()
        devBundle.parent.createDirectories()

        val tempPatchDir = createTempDirectory("devBundlePatches")
        try {
            generatePatches(tempPatchDir)

            val dataDir = "data"
            val patchesDir = "patches"
            val config = createBundleConfig(dataDir, patchesDir)

            devBundle.writeZip().use { zip ->
                zip.getPath("config.json").bufferedWriter(Charsets.UTF_8).use { writer ->
                    gson.toJson(config, writer)
                }
                zip.getPath("data-version.txt").writeText(currentDataVersion.toString())

                val dataZip = zip.getPath(dataDir)
                dataZip.createDirectories()
                reobfMappingsFile.path.copyTo(dataZip.resolve(reobfMappingsFileName))
                mojangMappedPaperclipFile.path.copyTo(dataZip.resolve(mojangMappedPaperclipFileName))
                atFile.path.copyTo(dataZip.resolve(atFileName))

                val patchesZip = zip.getPath(patchesDir)
                tempPatchDir.copyRecursivelyTo(patchesZip)
            }
        } finally {
            tempPatchDir.deleteRecursive()
        }
    }

    private fun generatePatches(output: Path) {
        val workingDir = layout.cache.resolve(paperTaskOutput("tmpdir"))
        workingDir.deleteRecursive()
        workingDir.createDirectories()
        sourceDir.path.copyRecursivelyTo(workingDir)

        Files.walk(workingDir).use { stream ->
            decompiledJar.path.openZip().use { decompJar ->
                val decompRoot = decompJar.rootDirectories.single()

                for (file in stream) {
                    if (file.isDirectory()) {
                        continue
                    }
                    val relativeFile = file.relativeTo(workingDir)
                    val relativeFilePath = relativeFile.invariantSeparatorsPathString
                    val decompFile = decompRoot.resolve(relativeFilePath)

                    if (decompFile.notExists()) {
                        val outputFile = output.resolve(relativeFilePath)
                        outputFile.parent.createDirectories()
                        file.copyTo(outputFile)
                    } else {
                        val diffText = diffFiles(relativeFilePath, decompFile, file)
                        val patchName = relativeFile.name + ".patch"
                        val outputFile = output.resolve(relativeFilePath).resolveSibling(patchName)
                        if (diffText.isNotBlank()) {
                            outputFile.parent.createDirectories()
                            outputFile.writeText(diffText)
                        }
                    }
                }
            }
        }

        workingDir.deleteRecursive()
    }

    private fun diffFiles(fileName: String, original: Path, patched: Path): String {
        val dir = createTempDirectory("diff")
        try {
            val oldFile = dir.resolve("old.java")
            val newFile = dir.resolve("new.java")
            original.copyTo(oldFile)
            patched.copyTo(newFile)

            val args = listOf(
                "--color=never",
                "-ud",
                "--label",
                "a/$fileName",
                oldFile.absolutePathString(),
                "--label",
                "b/$fileName",
                newFile.absolutePathString(),
            )

            return runDiff(dir, args)
        } finally {
            dir.deleteRecursive()
        }
    }

    private fun runDiff(dir: Path?, args: List<String>): String {
        val cmd = listOf("diff") + args
        val process = ProcessBuilder(cmd)
            .directory(dir)
            .start()

        val errBytes = ByteArrayOutputStream()
        val errFuture = redirect(process.errorStream, errBytes)
        val outBytes = ByteArrayOutputStream()
        val outFuture = redirect(process.inputStream, outBytes)

        if (!process.waitFor(10L, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw PaperweightException("Command '${cmd.joinToString(" ")}' did not finish after 10 seconds, killed process")
        }
        errFuture.get(500L, TimeUnit.MILLISECONDS)
        outFuture.get(500L, TimeUnit.MILLISECONDS)
        val err = asString(errBytes)
        val exit = process.exitValue()
        if (exit != 0 && exit != 1 || err.isNotBlank()) {
            throw PaperweightException("Error (exit code $exit) executing '${cmd.joinToString(" ")}':\n$err")
        }

        return asString(outBytes)
    }

    private fun asString(out: ByteArrayOutputStream) = String(out.toByteArray(), Charset.defaultCharset())
        .replace(System.lineSeparator(), "\n")

    @Suppress("SameParameterValue")
    private fun createBundleConfig(dataTargetDir: String, patchTargetDir: String): DevBundleConfig {
        return DevBundleConfig(
            minecraftVersion = minecraftVersion.get(),
            mappedServerCoordinates = serverCoordinates.get(),
            apiCoordinates = "${apiCoordinates.get()}:${serverVersion.get()}",
            mojangApiCoordinates = "${mojangApiCoordinates.get()}:${serverVersion.get()}",
            buildData = createBuildDataConfig(dataTargetDir),
            decompile = createDecompileRunner(),
            remapper = createRemapDep(),
            patchDir = patchTargetDir
        )
    }

    private fun createBuildDataConfig(targetDir: String): BuildData {
        return BuildData(
            paramMappings = MavenDep(paramMappingsUrl.get(), listOf(paramMappingsCoordinates.get())),
            reobfMappingsFile = "$targetDir/$reobfMappingsFileName",
            accessTransformFile = "$targetDir/$atFileName",
            mojangMappedPaperclipFile = "$targetDir/$mojangMappedPaperclipFileName",
            vanillaJarIncludes = vanillaJarIncludes.get(),
            compileDependencies = determineLibraries(vanillaServerLibraries.get()).sorted(),
            runtimeDependencies = collectRuntimeDependencies().map { it.coordinates }.sorted(),
            libraryRepositories = libraryRepositories.get(),
            relocations = emptyList(), // Nothing is relocated in the dev bundle as of 1.20.5
            minecraftRemapArgs = TinyRemapper.minecraftRemapArgs,
            pluginRemapArgs = TinyRemapper.pluginRemapArgs,
        )
    }

    private fun determineLibraries(vanillaServerLibraries: List<String>): Set<String> {
        val new = arrayListOf<ModuleId>()

        // yes this is not configuration cache compatible, but the task isn't even without this,
        // and what we want here are the dependencies declared in the server build file,
        // not the runtime classpath, which would flatten transitive deps of the api for example.
        for (dependency in serverProject.get().configurations.getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME).dependencies) {
            // don't want project dependencies
            if (dependency !is ExternalModuleDependency) {
                continue
            }
            val version = listOfNotNull(
                dependency.versionConstraint.strictVersion,
                dependency.versionConstraint.requiredVersion,
                dependency.versionConstraint.preferredVersion,
                dependency.version
            ).first { it.isNotBlank() }
            new += ModuleId(dependency.group ?: error("Missing group for $dependency"), dependency.name, version)
        }

        for (vanillaLib in vanillaServerLibraries) {
            val vanilla = ModuleId.parse(vanillaLib)
            if (new.none { it.group == vanilla.group && it.name == vanilla.name && it.classifier == vanilla.classifier }) {
                new += vanilla
            }
        }

        return new.map { it.toString() }.toSet()
    }

    private val ResolvedArtifactResult.coordinates: String
        get() = ModuleId.fromIdentifier(id).toString()

    private fun collectRuntimeDependencies(): Set<ResolvedArtifactResult> =
        runtimeConfiguration.get().incoming.artifacts.artifacts.filterTo(HashSet()) {
            it.id.componentIdentifier is ModuleComponentIdentifier
        }

    private fun createDecompileRunner(): Runner {
        return Runner(
            dep = determineMavenDep(decompilerUrl, decompilerConfig),
            args = vineFlowerArgList
        )
    }

    private fun createRemapDep(): MavenDep =
        determineMavenDep(remapperUrl, remapperConfig)

    data class DevBundleConfig(
        val minecraftVersion: String,
        val mappedServerCoordinates: String,
        val apiCoordinates: String,
        val mojangApiCoordinates: String,
        val buildData: BuildData,
        val decompile: Runner,
        val remapper: MavenDep,
        val patchDir: String
    )

    data class BuildData(
        val paramMappings: MavenDep,
        val reobfMappingsFile: String,
        val accessTransformFile: String,
        val mojangMappedPaperclipFile: String,
        val vanillaJarIncludes: List<String>,
        val compileDependencies: List<String>,
        val runtimeDependencies: List<String>,
        val libraryRepositories: List<String>,
        val relocations: List<Relocation>,
        val minecraftRemapArgs: List<String>,
        val pluginRemapArgs: List<String>,
    )

    data class Runner(val dep: MavenDep, val args: List<String>)

    companion object {
        const val unsupportedEnvironmentPropName: String = "paperweight.generateDevBundle.ignoreUnsupportedEnvironment"

        const val atFileName = "transform.at"
        const val reobfMappingsFileName = "$DEOBF_NAMESPACE-$SPIGOT_NAMESPACE-reobf.tiny"
        const val mojangMappedPaperclipFileName = "paperclip-$DEOBF_NAMESPACE.jar"

        // Should be bumped when the dev bundle config/contents changes in a way which will require users to update paperweight
        const val currentDataVersion = 3

        fun createCoordinatesFor(project: Project): String =
            sequenceOf(project.group, project.name.lowercase(Locale.ENGLISH), "userdev-" + project.version).joinToString(":")
    }

    private fun checkEnvironment() {
        val diffVersion = runDiff(null, listOf("--version")) + " " // add whitespace so pattern still works even with eol
        val matcher = Pattern.compile("diff \\(GNU diffutils\\) (.*?)\\s").matcher(diffVersion)
        if (matcher.find()) {
            logger.lifecycle("Using 'diff (GNU diffutils) {}'.", matcher.group(1))
            return
        }

        logger.warn("Non-GNU diffutils diff detected, '--version' returned:\n{}", diffVersion)
        if (this.ignoreUnsupportedEnvironment.getOrElse(false)) {
            logger.warn("Ignoring unsupported environment as per user configuration.")
        } else {
            throw PaperweightException(
                "Dev bundle generation is running in an unsupported environment (see above log messages).\n" +
                    "You can ignore this and attempt to generate a dev bundle anyways by setting the '$unsupportedEnvironmentPropName' Gradle " +
                    "property to 'true'."
            )
        }
    }
}
