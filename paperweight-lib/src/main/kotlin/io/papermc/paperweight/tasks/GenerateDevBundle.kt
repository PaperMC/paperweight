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
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateDevBundle : DefaultTask() {

    @get:InputDirectory
    abstract val mainJavaDir: DirectoryProperty

    @get:InputDirectory
    abstract val vanillaJavaDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchedJavaDir: DirectoryProperty

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    abstract val mojangMappedPaperclipFile: RegularFileProperty

    @get:Input
    abstract val libraryRepositories: ListProperty<String>

    @get:Input
    abstract val pluginRemapperUrl: Property<String>

    @get:Classpath
    abstract val pluginRemapperConfig: Property<Configuration>

    @get:Input
    abstract val macheUrl: Property<String>

    @get:Classpath
    abstract val macheConfig: Property<Configuration>

    @get:InputFile
    abstract val reobfMappingsFile: RegularFileProperty

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
        mainJavaDir.path.copyRecursivelyTo(workingDir)
        patchedJavaDir.path.copyRecursivelyTo(workingDir)
        workingDir.resolve(".git").deleteRecursive()

        Files.walk(workingDir).use { stream ->
            val oldSrc = vanillaJavaDir.path
            for (file in stream) {
                if (file.isDirectory()) {
                    continue
                }
                val relativeFile = file.relativeTo(workingDir)
                val relativeFilePath = relativeFile.invariantSeparatorsPathString
                val decompFile = oldSrc.resolve(relativeFilePath)

                if (decompFile.exists()) {
                    val diffText = diffFiles(relativeFilePath, decompFile, file)
                    val patchName = relativeFile.name + ".patch"
                    val outputFile = output.resolve(relativeFilePath).resolveSibling(patchName)
                    if (diffText.isNotBlank()) {
                        outputFile.parent.createDirectories()
                        outputFile.writeText(diffText)
                    }
                } else {
                    val outputFile = output.resolve(relativeFilePath)
                    outputFile.parent.createDirectories()
                    file.copyTo(outputFile)
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
            mache = createMacheDep(),
            pluginRemapper = createRemapDep(),
            patchDir = patchTargetDir,
            reobfMappingsFile = "$dataTargetDir/$reobfMappingsFileName",
            mojangMappedPaperclipFile = "$dataTargetDir/$mojangMappedPaperclipFileName",
            libraryRepositories = libraryRepositories.get(),
            pluginRemapArgs = TinyRemapper.pluginRemapArgs,
        )
    }

    private fun createRemapDep(): MavenDep =
        determineMavenDep(pluginRemapperUrl, pluginRemapperConfig)

    private fun createMacheDep(): MavenDep =
        determineMavenDep(macheUrl, macheConfig)

    data class DevBundleConfig(
        val minecraftVersion: String,
        val mache: MavenDep,
        val pluginRemapper: MavenDep,
        val patchDir: String,
        val reobfMappingsFile: String,
        val mojangMappedPaperclipFile: String,
        val libraryRepositories: List<String>,
        val pluginRemapArgs: List<String>,
    )

    companion object {
        const val unsupportedEnvironmentPropName: String = "paperweight.generateDevBundle.ignoreUnsupportedEnvironment"

        const val reobfMappingsFileName = "$DEOBF_NAMESPACE-$SPIGOT_NAMESPACE-reobf.tiny"
        const val mojangMappedPaperclipFileName = "paperclip-$DEOBF_NAMESPACE.jar"

        // Should be bumped when the dev bundle config/contents changes in a way which will require users to update paperweight
        const val currentDataVersion = 6
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
