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

import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.McDev
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.path
import java.io.File
import net.fabricmc.lorenztiny.TinyMappingFormat
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.get

abstract class RemapPatches : BaseTask() {

    @get:InputDirectory
    abstract val inputPatchDir: DirectoryProperty
    @get:InputDirectory
    abstract val apiPatchDir: DirectoryProperty

    @get:InputFile
    abstract val mappingsFile: RegularFileProperty
    @get:InputFile
    abstract val ats: RegularFileProperty

    @get:Classpath
    abstract val classpathJars: ListProperty<RegularFile>

    @get:InputDirectory
    abstract val spigotApiDir: DirectoryProperty
    @get:InputDirectory
    abstract val spigotServerDir: DirectoryProperty
    @get:InputFile
    abstract val spigotDecompJar: RegularFileProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty
    @get:InputFile
    abstract val libraryImports: RegularFileProperty

    @get:OutputDirectory
    abstract val outputPatchDir: DirectoryProperty

    @get:Internal
    @get:Option(option = "skip-patches", description = "For resuming, skip first # of patches (e.g. --skip-patches=300)")
    abstract val skipPatches: Property<String>

    @get:Internal
    @get:Option(option = "limit-patches", description = "For testing, you can limit the # of patches (e.g. --limit-patches=10)")
    abstract val limitPatches: Property<String>

    override fun init() {
        skipPatches.convention("0")
        limitPatches.convention("-1")
    }

    @TaskAction
    fun run() {
        val skip = skipPatches.get().toInt()
        var limit = limitPatches.get().toInt()

        // Check patches
        val patches = inputPatchDir.file.listFiles() ?: return run {
            println("No input patches found")
        }

        if (limit == -1) {
            limit = patches.size
        }

        patches.sort()

        val mappings = TinyMappingFormat.STANDARD.read(mappingsFile.path, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)

        // This should pull in any libraries needed for type bindings
        val configFiles = project.project(":Paper-Server").configurations["runtimeClasspath"].resolve()
        val classpathFiles = classpathJars.get().map { it.asFile } + configFiles

        // Remap output directory, after each output this directory will be re-named to the input directory below for
        // the next remap operation
        println("setting up repo")
        val tempApiDir = createWorkDir("patch-remap-api", source = spigotApiDir.file, recreate = skip == 0)
        val tempInputDir = createWorkDirByCloning("patch-remap-input", source = spigotServerDir.file, recreate = skip == 0)
        val tempOutputDir = createWorkDir("patch-remap-output")

        val sourceInputDir = tempInputDir.resolve("src/main/java")

        PatchSourceRemapWorker(
            mappings,
            AccessTransformFormats.FML.read(ats.path),
            listOf(*classpathFiles.toTypedArray(), tempApiDir.resolve("src/main/java")).map { it.toPath() },
            sourceInputDir.toPath(),
            tempOutputDir.toPath()
        ).let { remapper ->
            val patchApplier = PatchApplier("remapped", "old", tempInputDir)

            if (skip == 0) {
                // We need to include any missing classes for the patches later on
                McDev.importMcDev(patches, spigotDecompJar.file, libraryImports.file, mcLibrariesDir.file, tempInputDir.resolve("src/main/java"))

                patchApplier.commitInitialSource() // Initial commit of Spigot sources
                patchApplier.checkoutRemapped() // Switch to remapped branch without checking out files

                remapper.remap() // Remap to new mappings
                patchApplier.commitInitialRemappedSource() // Initial commit of Spigot sources mapped to new mappings
                patchApplier.checkoutOld() // Normal checkout back to Spigot mappings branch
            }

            // remapping renames params, we don't want to leak these changes into the patches below
//            println("setting up remap commit")
//            patchApplier.commitRemappingDifferences(remapper)

            // Repo setup is done, we can begin the patch loop now
            patches.asSequence().drop(skip).take(limit).forEach { patch ->
                println("===========================")
                println("attempting to remap $patch")
                println("===========================")
                patchApplier.applyPatch(patch) // Apply patch on Spigot mappings
                patchApplier.recordCommit() // Keep track of commit author, message, and time
                patchApplier.checkoutRemapped() // Switch to remapped branch without checkout out files
                remapper.remap() // Remap to new mappings
                patchApplier.commitChanges() // Commit the changes
                patchApplier.checkoutOld() // Normal checkout back to Spigot mappings branch
                println("===========================")
                println("done remapping patch $patch")
                println("===========================")
            }

            patchApplier.generatePatches(outputPatchDir.file)
        }
    }

    private fun createWorkDir(name: String, source: File? = null, recreate: Boolean = true): File {
        return layout.cache.resolve("paperweight").resolve(name).apply {
            if (recreate) {
                deleteRecursively()
                mkdirs()
                source?.copyRecursively(this)
            }
        }
    }

    private fun createWorkDirByCloning(name: String, source: File, recreate: Boolean = true): File {
        val workDir = layout.cache.resolve("paperweight")
        return workDir.resolve(name).apply {
            if (recreate) {
                deleteRecursively()
                mkdirs()
                Git(workDir)("clone", source.absolutePath, this.absolutePath).executeSilently()
            }
        }
    }
}
