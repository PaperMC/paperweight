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

import io.papermc.paperweight.core.tasks.patching.ApplyFilePatches
import io.papermc.paperweight.core.tasks.patching.ApplyFilePatchesFuzzy
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.softspoon.ApplyFeaturePatches
import io.papermc.paperweight.tasks.softspoon.FixupFilePatches
import io.papermc.paperweight.tasks.softspoon.RebuildFilePatches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class PatchingTasks(
    private val project: Project,
    private val forkName: String,
    private val patchSetName: String,
    private val taskGroup: String,
    private val readOnly: Boolean,
    private val filePatchDir: DirectoryProperty,
    private val rejectsDir: DirectoryProperty,
    private val featurePatchDir: DirectoryProperty,
    private val baseDir: Provider<Directory>,
    private val gitFilePatches: Provider<Boolean>,
    private val outputDir: Path,
    private val tasks: TaskContainer = project.tasks,
) {
    private val namePart: String = if (readOnly) "${forkName.capitalized()}${patchSetName.capitalized()}" else patchSetName.capitalized()

    private fun ApplyFilePatches.configureApplyFilePatches() {
        group = taskGroup
        description = "Applies $patchSetName file patches"

        input.set(baseDir)
        if (readOnly) {
            output.set(layout.cache.resolve(paperTaskOutput()))
        } else {
            output.set(outputDir)
        }
        patches.set(filePatchDir.fileExists(project))
        rejects.set(rejectsDir)
        gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        baseRef.set("base")
        identifier = "$forkName $patchSetName"
    }

    val applyFilePatches = tasks.register<ApplyFilePatches>("apply${namePart}FilePatches") {
        configureApplyFilePatches()
    }

    val applyFilePatchesFuzzy = tasks.register<ApplyFilePatchesFuzzy>("apply${namePart}FilePatchesFuzzy") {
        configureApplyFilePatches()
    }

    val applyFeaturePatches = tasks.register<ApplyFeaturePatches>("apply${namePart}FeaturePatches") {
        group = taskGroup
        description = "Applies $patchSetName feature patches"
        dependsOn(applyFilePatches)

        repo.set(outputDir)
        if (readOnly) {
            base.set(applyFilePatches.flatMap { it.output })
        }
        patches.set(featurePatchDir.fileExists(project))
    }

    val applyPatches = tasks.register<Task>("apply${namePart}Patches") {
        group = taskGroup
        description = "Applies all $patchSetName patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildFilePatchesName = "rebuild${namePart}FilePatches"
    val fixupFilePatchesName = "fixup${namePart}FilePatches"
    val rebuildFeaturePatchesName = "rebuild${namePart}FeaturePatches"
    val rebuildPatchesName = "rebuild${namePart}Patches"

    init {
        if (!readOnly) {
            setupWritable()
        }
    }

    private fun setupWritable() {
        listOf(
            applyFilePatches,
            applyFilePatchesFuzzy,
            applyFeaturePatches,
        ).forEach {
            it.configure {
                doNotTrackState("Always run when requested")
            }
        }

        val rebuildFilePatches = tasks.register<RebuildFilePatches>(rebuildFilePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName file patches"

            base.set(baseDir)
            input.set(outputDir)
            patches.set(filePatchDir)
            gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        }

        val fixupFilePatches = tasks.register<FixupFilePatches>(fixupFilePatchesName) {
            group = taskGroup
            description = "Puts the currently tracked source changes into the $patchSetName file patches commit"

            repo.set(outputDir)
            upstream.set("base")
        }

        val rebuildFeaturePatches = tasks.register<RebuildGitPatches>(rebuildFeaturePatchesName) {
            group = taskGroup
            description = "Rebuilds $patchSetName feature patches"
            dependsOn(rebuildFilePatches)

            inputDir.set(outputDir)
            patchDir.set(featurePatchDir)
            baseRef.set("file")
        }

        val rebuildPatches = tasks.register<Task>(rebuildPatchesName) {
            group = taskGroup
            description = "Rebuilds all $patchSetName patches"
            dependsOn(rebuildFilePatches, rebuildFeaturePatches)
        }
    }
}
