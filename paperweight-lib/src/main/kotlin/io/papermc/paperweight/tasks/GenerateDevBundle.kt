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

import codechicken.diffpatch.cli.DiffOperation
import codechicken.diffpatch.util.LogLevel
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
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
    abstract val macheUrl: Property<String>

    @get:Input
    abstract val macheDep: Property<String>

    @get:InputFile
    abstract val reobfMappingsFile: RegularFileProperty

    @get:OutputFile
    abstract val devBundleFile: RegularFileProperty

    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun run() {
        temporaryDir.toPath().deleteRecursive()
        temporaryDir.toPath().createDirectories()
        val devBundle = devBundleFile.path
        devBundle.deleteForcefully()
        devBundle.createParentDirectories()

        val tempPatchDir = temporaryDir.toPath().resolve("patches")
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

        temporaryDir.toPath().deleteRecursive()
    }

    private fun generatePatches(output: Path) {
        val workingDir = temporaryDir.toPath().resolve("work")
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
                    val patchName = relativeFile.name + ".patch"
                    val outputFile = output.resolve(relativeFilePath).resolveSibling(patchName)
                    diffFiles(relativeFilePath, decompFile, file)
                        ?.copyTo(outputFile.createParentDirectories())
                } else {
                    val outputFile = output.resolve(relativeFilePath)
                    file.copyTo(outputFile.createParentDirectories())
                }
            }
        }
    }

    private fun diffFiles(fileName: String, original: Path, patched: Path): Path? {
        val dir = temporaryDir.toPath().resolve("diff-work")
        dir.deleteRecursive()
        dir.createDirectories()
        val a = dir.resolve("a")
        val oldFile = a.resolve(fileName).createParentDirectories()
        val b = dir.resolve("b")
        val newFile = b.resolve(fileName).createParentDirectories()
        val patchOut = dir.resolve("out")
        original.copyTo(oldFile)
        patched.copyTo(newFile)

        val logFile = temporaryDir.toPath().resolve("diff-log/${fileName.replace("/", "_")}.txt")
            .createParentDirectories()
        PrintStream(logFile.toFile(), Charsets.UTF_8).use { logOut ->
            DiffOperation.builder()
                .logTo(logOut)
                .aPath(a)
                .bPath(b)
                .outputPath(patchOut, null)
                .autoHeader(true)
                .level(LogLevel.ALL)
                .lineEnding("\n")
                .context(3)
                .summary(true)
                .build()
                .operate()
        }

        return patchOut.resolve("$fileName.patch").takeIf { it.isRegularFile() }
    }

    @Suppress("SameParameterValue")
    private fun createBundleConfig(dataTargetDir: String, patchTargetDir: String): DevBundleConfig {
        return DevBundleConfig(
            minecraftVersion = minecraftVersion.get(),
            mache = createMacheDep(),
            patchDir = patchTargetDir,
            reobfMappingsFile = "$dataTargetDir/$reobfMappingsFileName",
            mojangMappedPaperclipFile = "$dataTargetDir/$mojangMappedPaperclipFileName",
            libraryRepositories = libraryRepositories.get(),
            pluginRemapArgs = TinyRemapper.pluginRemapArgs,
        )
    }

    private fun createMacheDep(): MavenDep =
        macheUrl.zip(macheDep) { url, dep -> MavenDep(url, listOf(dep)) }.get()

    data class DevBundleConfig(
        val minecraftVersion: String,
        val mache: MavenDep,
        val patchDir: String,
        val reobfMappingsFile: String,
        val mojangMappedPaperclipFile: String,
        val libraryRepositories: List<String>,
        val pluginRemapArgs: List<String>,
    )

    companion object {
        const val reobfMappingsFileName = "$DEOBF_NAMESPACE-$SPIGOT_NAMESPACE-reobf.tiny"
        const val mojangMappedPaperclipFileName = "paperclip-$DEOBF_NAMESPACE.jar"

        // Should be bumped when the dev bundle config/contents changes in a way which will require users to update paperweight
        const val currentDataVersion = 6
    }
}
