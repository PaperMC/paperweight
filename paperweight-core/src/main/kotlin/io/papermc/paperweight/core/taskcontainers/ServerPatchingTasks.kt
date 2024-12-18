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
import io.papermc.paperweight.core.tasks.ForkSetup
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.softspoon.ApplyFeaturePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatchesFuzzy
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
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class ServerPatchingTasks(
    private val project: Project,
    private val configName: String,
    private val softspoon: SoftSpoonTasks,
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
    private val outputSrc: Path = outputRoot.resolve("src/vanilla/java"),
    private val outputResources: Path = outputRoot.resolve("src/vanilla/resources"),
    private val outputSrcFile: Path = outputRoot.resolve("file/src/vanilla/java"),
    private val tasks: TaskContainer = project.tasks
) {
    private val taskGroup = if (readOnly) "upstream vanilla server patching" else "vanilla server patching"
    private fun Task.group() {
        group = taskGroup
    }

    private val namePart = if (readOnly) configName.capitalized() else ""

    private fun ApplyFilePatches.configureApplyFilePatches() {
        group()
        description = "Applies $configName file patches to the vanilla sources"

        input.set(baseSources)
        if (readOnly) {
            output.set(outputSrcFile)
        } else {
            output.set(outputSrc)
        }
        patches.set(sourcePatchDir.fileExists(project))
        rejects.set(rejectsDir)
        gitFilePatches.set(this@ServerPatchingTasks.gitFilePatches)
        identifier = configName
    }

    val applySourcePatches = tasks.register<ApplyFilePatches>("apply${namePart}VanillaSourcePatches") {
        configureApplyFilePatches()
    }

    val applySourcePatchesFuzzy = tasks.register<ApplyFilePatchesFuzzy>("apply${namePart}VanillaSourcePatchesFuzzy") {
        configureApplyFilePatches()
    }

    val applyResourcePatches = tasks.register<ApplyFilePatches>("apply${namePart}VanillaResourcePatches") {
        group()
        description = "Applies $configName file patches to the vanilla resources"

        input.set(baseResources)
        output.set(outputResources)
        patches.set(resourcePatchDir.fileExists(project))
        // TODO rejects?
        gitFilePatches.set(this@ServerPatchingTasks.gitFilePatches)
        identifier = configName
    }

    val applyFilePatches = tasks.register<Task>("apply${namePart}VanillaFilePatches") {
        group()
        description = "Applies all $configName vanilla file patches"
        dependsOn(applySourcePatches, applyResourcePatches)
    }

    val applyFeaturePatches = tasks.register<ApplyFeaturePatches>("apply${namePart}VanillaFeaturePatches") {
        group()
        description = "Applies all $configName vanilla feature patches"
        dependsOn(applyFilePatches)

        if (readOnly) {
            base.set(applySourcePatches.flatMap { it.output })
        }
        repo.set(outputSrc)
        patches.set(featurePatchDir.fileExists(project))
    }

    val applyPatches = tasks.register<Task>("apply${namePart}VanillaPatches") {
        group()
        description = "Applies all $configName vanilla patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildSourcePatchesName = "rebuild${namePart}VanillaSourcePatches"
    val rebuildResourcePatchesName = "rebuild${namePart}VanillaResourcePatches"
    val rebuildFilePatchesName = "rebuild${namePart}VanillaFilePatches"
    val rebuildFeaturePatchesName = "rebuild${namePart}VanillaFeaturePatches"
    val rebuildPatchesName = "rebuild${namePart}VanillaPatches"

    init {
        if (!readOnly) {
            setupWritable()
        }
    }

    fun setupFork(config: ForkConfig) {
        val collectAccessTransform = tasks.register<CollectATsFromPatches>("collect${namePart}ATsFromPatches") {
            patchDir.set(featurePatchDir.fileExists(project))
        }

        val mergeCollectedAts = tasks.register<MergeAccessTransforms>("merge${namePart}ATs") {
            firstFile.set(additionalAts.fileExists(project))
            secondFile.set(collectAccessTransform.flatMap { it.outputFile })
        }

        val importLibFiles = tasks.register<ImportLibraryFiles>("import${namePart}LibraryFiles") {
            patches.from(config.featurePatchDir, config.sourcePatchDir)
            devImports.set(config.devImports.fileExists(project))
            libraryFileIndex.set(softspoon.indexLibraryFiles.flatMap { it.outputFile })
            libraries.from(softspoon.indexLibraryFiles.map { it.libraries })
        }

        val setup = tasks.register<ForkSetup>("run${namePart}VanillaSetup") {
            description = "Applies $configName ATs and library imports to vanilla sources"

            inputDir.set(baseSources)
            outputDir.set(layout.cache.resolve(paperTaskOutput()))
            identifier.set(configName.capitalized())

            libraryImports.set(importLibFiles.flatMap { it.outputDir })
            atFile.set(mergeCollectedAts.flatMap { it.outputFile })
            ats.jst.from(softspoon.jstConfig)
            ats.jstClasspath.from(project.configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
        }

        applySourcePatches.configure {
            input.set(setup.flatMap { it.outputDir })
        }
        applySourcePatchesFuzzy.configure {
            input.set(setup.flatMap { it.outputDir })
        }
        val name = "rebuild${namePart}VanillaSourcePatches"
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
            description = "Rebuilds $configName file patches to the vanilla sources"

            base.set(baseSources)
            input.set(outputSrc)
            patches.set(sourcePatchDir)
            gitFilePatches.set(this@ServerPatchingTasks.gitFilePatches)

            ats.jstClasspath.from(softspoon.macheMinecraft)
            ats.jst.from(softspoon.jstConfig)
            atFile.set(additionalAts.fileExists(project))
            atFileOut.set(additionalAts.fileExists(project))
        }

        val rebuildResourcePatches = tasks.register<RebuildFilePatches>(rebuildResourcePatchesName) {
            group()
            description = "Rebuilds $configName file patches to the vanilla resources"

            base.set(baseResources)
            input.set(outputResources)
            patches.set(resourcePatchDir)
        }

        val rebuildFilePatches = tasks.register<Task>(rebuildFilePatchesName) {
            group()
            description = "Rebuilds all $configName file patches to vanilla"
            dependsOn(rebuildSourcePatches, rebuildResourcePatches)
        }

        val rebuildFeaturePatches = tasks.register<RebuildGitPatches>(rebuildFeaturePatchesName) {
            group()
            description = "Rebuilds all $configName feature patches to the vanilla sources"
            dependsOn(rebuildFilePatches)

            inputDir.set(outputSrc)
            patchDir.set(featurePatchDir)
            baseRef.set("file")
        }

        val rebuildPatches = tasks.register<Task>(rebuildPatchesName) {
            group()
            description = "Rebuilds all $configName patches to vanilla"
            dependsOn(rebuildFilePatches, rebuildFeaturePatches)
        }

        val fixupSourcePatches = tasks.register<FixupFilePatches>("fixup${namePart}VanillaSourcePatches") {
            group()
            description = "Puts the currently tracked source changes into the $configName vanilla sources file patches commit"

            repo.set(outputSrc)
            upstream.set("upstream/main")
        }

        val fixupResourcePatches = tasks.register<FixupFilePatches>("fixup${namePart}VanillaResourcePatches") {
            group()
            description = "Puts the currently tracked resource changes into the $configName vanilla resources file patches commit"

            repo.set(outputResources)
            upstream.set("upstream/main")
        }
    }
}
