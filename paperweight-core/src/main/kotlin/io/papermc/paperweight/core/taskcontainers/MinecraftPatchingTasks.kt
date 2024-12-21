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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.extension.ForkConfig
import io.papermc.paperweight.core.tasks.SetupForkMinecraftSources
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatchesFuzzy
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.softspoon.ApplyFeaturePatches
import io.papermc.paperweight.tasks.softspoon.FixupFilePatches
import io.papermc.paperweight.tasks.softspoon.ImportLibraryFiles
import io.papermc.paperweight.tasks.softspoon.RebuildFilePatches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class MinecraftPatchingTasks(
    private val project: Project,
    private val configName: String,
    paper: Boolean,
    private val coreTasks: CoreTasks,
    private val readOnly: Boolean,
    private val sourcePatchDir: DirectoryProperty,
    private val rejectsDir: DirectoryProperty,
    private val resourcePatchDir: DirectoryProperty,
    private val featurePatchDir: DirectoryProperty,
    private val additionalAts: RegularFileProperty,
    private val baseSources: Provider<Directory>,
    private val baseResources: Provider<Directory>,
    private val gitFilePatches: Provider<Boolean>,
    outputRoot: Path,
    private val outputSrc: Path = outputRoot.resolve("src/minecraft/java"),
    private val outputResources: Path = outputRoot.resolve("src/minecraft/resources"),
    private val outputSrcFile: Path = outputRoot.resolve("file/src/minecraft/java"),
    private val tasks: TaskContainer = project.tasks
) {
    private val taskGroup = if (readOnly) "upstream minecraft patching" else "minecraft patching"
    private fun Task.group() {
        group = taskGroup
    }

    private val namePart = if (readOnly) "${configName.capitalized()}Minecraft" else if (paper) "" else "Minecraft"

    private fun ApplyFilePatches.configureApplyFilePatches() {
        group()
        description = "Applies $configName file patches to the Minecraft sources"

        input.set(baseSources)
        if (readOnly) {
            output.set(outputSrcFile)
        } else {
            output.set(outputSrc)
        }
        patches.set(sourcePatchDir.fileExists(project))
        rejects.set(rejectsDir)
        gitFilePatches.set(this@MinecraftPatchingTasks.gitFilePatches)
        identifier = configName
    }

    val applySourcePatches = tasks.register<ApplyFilePatches>("apply${namePart}SourcePatches") {
        configureApplyFilePatches()
    }

    val applySourcePatchesFuzzy = tasks.register<ApplyFilePatchesFuzzy>("apply${namePart}SourcePatchesFuzzy") {
        configureApplyFilePatches()
    }

    val applyResourcePatches = tasks.register<ApplyFilePatches>("apply${namePart}ResourcePatches") {
        group()
        description = "Applies $configName file patches to the Minecraft resources"

        input.set(baseResources)
        output.set(outputResources)
        patches.set(resourcePatchDir.fileExists(project))
        // TODO rejects?
        gitFilePatches.set(this@MinecraftPatchingTasks.gitFilePatches)
        identifier = configName
    }

    val applyFilePatches = tasks.register<Task>("apply${namePart}FilePatches") {
        group()
        description = "Applies all $configName Minecraft file patches"
        dependsOn(applySourcePatches, applyResourcePatches)
    }

    val applyFeaturePatches = tasks.register<ApplyFeaturePatches>("apply${namePart}FeaturePatches") {
        group()
        description = "Applies all $configName Minecraft feature patches"
        dependsOn(applyFilePatches)

        if (readOnly) {
            base.set(applySourcePatches.flatMap { it.output })
        }
        repo.set(outputSrc)
        patches.set(featurePatchDir.fileExists(project))
    }

    val applyPatches = tasks.register<Task>("apply${namePart}Patches") {
        group()
        description = "Applies all $configName Minecraft patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildSourcePatchesName = "rebuild${namePart}SourcePatches"
    val rebuildResourcePatchesName = "rebuild${namePart}ResourcePatches"
    val rebuildFilePatchesName = "rebuild${namePart}FilePatches"
    val rebuildFeaturePatchesName = "rebuild${namePart}FeaturePatches"
    val rebuildPatchesName = "rebuild${namePart}Patches"

    init {
        if (!readOnly) {
            setupWritable()
        }
    }

    fun setupFork(config: ForkConfig) {
        val collectAccessTransform = tasks.register<CollectATsFromPatches>("collect${configName.capitalized()}ATsFromPatches") {
            patchDir.set(featurePatchDir.fileExists(project))
        }

        val mergeCollectedAts = tasks.register<MergeAccessTransforms>("merge${configName.capitalized()}ATs") {
            firstFile.set(additionalAts.fileExists(project))
            secondFile.set(collectAccessTransform.flatMap { it.outputFile })
        }

        val importLibFiles = tasks.register<ImportLibraryFiles>("import${configName.capitalized()}LibraryFiles") {
            patches.from(config.featurePatchDir, config.sourcePatchDir)
            devImports.set(config.devImports.fileExists(project))
            libraryFileIndex.set(coreTasks.indexLibraryFiles.flatMap { it.outputFile })
            libraries.from(coreTasks.indexLibraryFiles.map { it.libraries })
        }

        val setup = tasks.register<SetupForkMinecraftSources>("run${configName.capitalized()}Setup") {
            description = "Applies $configName ATs and library imports to Minecraft sources"

            inputDir.set(baseSources)
            outputDir.set(layout.cache.resolve(paperTaskOutput()))
            identifier.set(configName)

            libraryImports.set(importLibFiles.flatMap { it.outputDir })
            atFile.set(mergeCollectedAts.flatMap { it.outputFile })
            ats.jst.from(project.configurations.named(JST_CONFIG))
            ats.jstClasspath.from(project.configurations.named(MACHE_MINECRAFT_LIBRARIES_CONFIG))
        }

        applySourcePatches.configure {
            input.set(setup.flatMap { it.outputDir })
        }
        applySourcePatchesFuzzy.configure {
            input.set(setup.flatMap { it.outputDir })
        }
        val name = "rebuild${namePart}SourcePatches"
        if (name in tasks.names) {
            tasks.named<RebuildFilePatches>(name) {
                base.set(setup.flatMap { it.outputDir })
            }
        }
    }

    private fun setupWritable() {
        listOf(
            applySourcePatches,
            applySourcePatchesFuzzy,
            applyFeaturePatches,
            applyResourcePatches,
        ).forEach {
            it.configure {
                doNotTrackState("Always run when requested")
            }
        }

        val rebuildSourcePatches = tasks.register<RebuildFilePatches>(rebuildSourcePatchesName) {
            group()
            description = "Rebuilds $configName file patches to the Minecraft sources"

            base.set(baseSources)
            input.set(outputSrc)
            patches.set(sourcePatchDir)
            gitFilePatches.set(this@MinecraftPatchingTasks.gitFilePatches)

            ats.jstClasspath.from(project.configurations.named(MACHE_MINECRAFT_CONFIG))
            ats.jst.from(project.configurations.named(JST_CONFIG))
            atFile.set(additionalAts.fileExists(project))
            atFileOut.set(additionalAts.fileExists(project))
        }

        val rebuildResourcePatches = tasks.register<RebuildFilePatches>(rebuildResourcePatchesName) {
            group()
            description = "Rebuilds $configName file patches to the Minecraft resources"

            base.set(baseResources)
            input.set(outputResources)
            patches.set(resourcePatchDir)
        }

        val rebuildFilePatches = tasks.register<Task>(rebuildFilePatchesName) {
            group()
            description = "Rebuilds all $configName file patches to Minecraft"
            dependsOn(rebuildSourcePatches, rebuildResourcePatches)
        }

        val rebuildFeaturePatches = tasks.register<RebuildGitPatches>(rebuildFeaturePatchesName) {
            group()
            description = "Rebuilds all $configName feature patches to the Minecraft sources"
            dependsOn(rebuildFilePatches)

            inputDir.set(outputSrc)
            patchDir.set(featurePatchDir)
            baseRef.set("file")
        }

        val rebuildPatches = tasks.register<Task>(rebuildPatchesName) {
            group()
            description = "Rebuilds all $configName patches to Minecraft"
            dependsOn(rebuildFilePatches, rebuildFeaturePatches)
        }

        val fixupSourcePatches = tasks.register<FixupFilePatches>("fixup${namePart}SourcePatches") {
            group()
            description = "Puts the currently tracked source changes into the $configName Minecraft sources file patches commit"

            repo.set(outputSrc)
            upstream.set("upstream/main")
        }

        val fixupResourcePatches = tasks.register<FixupFilePatches>("fixup${namePart}ResourcePatches") {
            group()
            description = "Puts the currently tracked resource changes into the $configName Minecraft resources file patches commit"

            repo.set(outputResources)
            upstream.set("upstream/main")
        }
    }
}
