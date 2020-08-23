/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.tasks.finalizeProperties
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.mcpFile
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.listProperty
import java.io.File
import java.util.zip.ZipFile

open class RemapPatches : DefaultTask() {

    @InputDirectory
    val inputPatchDir: DirectoryProperty = project.objects.directoryProperty()
    @InputFile
    val sourceJar: RegularFileProperty = project.objects.fileProperty()
    @InputDirectory
    val apiPatchDir: DirectoryProperty = project.objects.directoryProperty()

    @InputFile
    val mappingsFile: RegularFileProperty = project.objects.fileProperty()

    @Classpath
    val classpathJars: ListProperty<RegularFile> = project.objects.listProperty()

    @InputDirectory
    val spigotApiDir: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val spigotServerDir: DirectoryProperty = project.objects.directoryProperty()
    @InputFile
    val spigotDecompJar: RegularFileProperty = project.objects.fileProperty()

    // For parameter name remapping
    // configFile is for constructors
    @InputFile
    val parameterNames: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()

    @OutputDirectory
    val outputPatchDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        finalizeProperties()

        // Check patches
        val patches = inputPatchDir.file.listFiles() ?: return run {
            println("No input patches found")
        }

        patches.sort()

        // Setup param remapping
        val config = mcpConfig(configFile)
        val constructors = mcpFile(configFile, config.data.constructors)

        val mappings = MappingFormats.TSRG.createReader(mappingsFile.file.toPath()).use { it.read() }

        // This should pull in any libraries needed for type bindings
        val configFiles = project.project(":Paper-Server").configurations["runtimeClasspath"].resolvedConfiguration.files
        val classpathFiles = classpathJars.get().map { it.asFile } + configFiles

        // Remap output directory, after each output this directory will be re-named to the input directory below for
        // the next remap operation
        val tempApiDir = createWorkDir("patch-remap-api", source = spigotApiDir.file)
        val tempInputDir = createWorkDir("patch-remap-input", source = spigotServerDir.file)
        val tempOutputDir = createWorkDir("patch-remap-output")

        val sourceInputDir = tempInputDir.resolve("src/main/java")
        sourceInputDir.deleteRecursively()
        sourceInputDir.mkdirs()

        project.copy {
            from(project.zipTree(sourceJar.file))
            into(sourceInputDir)
        }

        tempInputDir.resolve(".git").deleteRecursively()

        PatchSourceRemapWorker(
            mappings,
            classpathFiles,
            tempApiDir.resolve("src/main/java"),
            parameterNames.file,
            constructors,
            sourceInputDir,
            tempOutputDir
        ).use { remapper ->
//        val patchApplier = PatchApplier("remapped", "old", tempInputDir)
            // Setup patch remapping repo
//        patchApplier.initRepo() // Create empty initial commit
//            remapper.remap() // Remap to Spigot mappings
            // We need to include any missing classes for the patches later on
//            importMcDev(patches, tempInputDir.resolve("src/main/java"))
//        patchApplier.commitInitialSource() // Initial commit of Spigot sources
//        patchApplier.checkoutRemapped() // Switch to remapped branch without checking out files
//            println("sleeping")
//            Thread.sleep(60_000)
            remapper.remapBack() // Remap to new mappings
//        patchApplier.commitInitialSource() // Initial commit of Spigot sources mapped to new mappings
//        patchApplier.checkoutOld() // Normal checkout back to Spigot mappings branch

            /*
        // Repo setup is done, we can begin the patch loop now
        remapper.remap() // Remap to to Spigot mappings
        patchApplier.applyPatch(patches.first()) // Apply patch on Spigot mappings
        patchApplier.recordCommit() // Keep track of commit author, message, and time
        patchApplier.checkoutRemapped() // Switch to remapped branch without checkout out files
        remapper.remapBack() // Remap to new mappings
        patchApplier.commitChanges() // Commit the changes
        patchApplier.checkoutOld() // Normal checkout back to Spigot mappings branch
         */
        }
    }

    private fun importMcDev(patches: Array<File>, inputDir: File) {
        val importMcDev = readMcDevNames(patches).asSequence()
            .map { inputDir.resolve("net/minecraft/server/$it.java") }
            .filter { !it.exists() }
            .toSet()
        ZipFile(spigotDecompJar.file).use { zipFile ->
            for (file in importMcDev) {
                val zipEntry = zipFile.getEntry(file.relativeTo(inputDir).path) ?: continue
                zipFile.getInputStream(zipEntry).use { input ->
                    file.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

    }

    private fun readMcDevNames(patches: Array<File>): Set<String> {
        val result = hashSetOf<String>()

        val prefix = "+++ b/src/main/java/net/minecraft/server/"
        val suffix = ".java"

        for (patch in patches) {
            patch.useLines { lines ->
                lines
                    .filter { it.startsWith(prefix) }
                    .map { it.substring(prefix.length, it.length - suffix.length) }
                    .forEach { result.add(it) }
            }
        }

        return result
    }

    private fun createWorkDir(name: String, source: File? = null): File {
        return project.cache.resolve("paperweight").resolve(name).apply {
//            deleteRecursively()
//            mkdirs()
//            source?.copyRecursively(this)
        }
    }
}
