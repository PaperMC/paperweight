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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.softspoon.ApplyFeaturePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatchesFuzzy
import io.papermc.paperweight.tasks.softspoon.FixupFilePatches
import io.papermc.paperweight.tasks.softspoon.RebuildFilePatches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class PatchingTasks(
    private val project: Project,
    private val configName: String,
    private val taskGroup: String,
    private val readOnly: Boolean,
    private val filePatchDir: DirectoryProperty,
    private val rejectsDir: DirectoryProperty,
    private val featurePatchDir: DirectoryProperty,
    private val baseDir: Provider<Directory>,
    private val gitFilePatches: Provider<Boolean>,
    private val outputDir: Path,
    private val ats: RegularFileProperty,
    private val jstClasspath: FileCollection,
    private val jstConfig: FileCollection,
    private val tasks: TaskContainer = project.tasks,
) {
    private fun ApplyFilePatches.configureApplyFilePatches() {
        group = taskGroup
        description = "Applies $configName file patches"

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
    }

    val applyFilePatches = tasks.register<ApplyFilePatches>("apply${configName.capitalized()}FilePatches") {
        configureApplyFilePatches()
    }

    val applyFilePatchesFuzzy = tasks.register<ApplyFilePatchesFuzzy>("apply${configName.capitalized()}FilePatchesFuzzy") {
        configureApplyFilePatches()
    }

    val applyFeaturePatches = tasks.register<ApplyFeaturePatches>("apply${configName.capitalized()}FeaturePatches") {
        group = taskGroup
        description = "Applies $configName feature patches"
        dependsOn(applyFilePatches)

        repo.set(outputDir)
        if (readOnly) {
            base.set(applyFilePatches.flatMap { it.output })
        }
        patches.set(featurePatchDir.fileExists(project))
    }

    val applyPatches = tasks.register<Task>("apply${configName.capitalized()}Patches") {
        group = taskGroup
        description = "Applies all $configName patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildFilePatchesName = "rebuild${configName.capitalized()}FilePatches"
    val fixupFilePatchesName = "fixup${configName.capitalized()}FilePatches"
    val rebuildFeaturePatchesName = "rebuild${configName.capitalized()}FeaturePatches"
    val rebuildPatchesName = "rebuild${configName.capitalized()}Patches"

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
            description = "Rebuilds $configName file patches"

            ats.jstClasspath.from(this@PatchingTasks.jstClasspath)
            ats.jst.from(jstConfig)
            atFile.set(this@PatchingTasks.ats.fileExists(project))
            atFileOut.set(this@PatchingTasks.ats.fileExists(project))

            base.set(baseDir)
            input.set(outputDir)
            patches.set(filePatchDir)
            gitFilePatches.set(this@PatchingTasks.gitFilePatches)
        }

        val fixupFilePatches = tasks.register<FixupFilePatches>(fixupFilePatchesName) {
            group = taskGroup
            description = "Puts the currently tracked source changes into the file patches commit"

            repo.set(outputDir)
        }

        val rebuildFeaturePatches = tasks.register<RebuildGitPatches>(rebuildFeaturePatchesName) {
            group = taskGroup
            description = "Rebuilds $configName feature patches"
            dependsOn(rebuildFilePatches)

            inputDir.set(outputDir)
            patchDir.set(featurePatchDir)
            baseRef.set("file")
        }

        val rebuildPatches = tasks.register<Task>(rebuildPatchesName) {
            group = taskGroup
            description = "Rebuilds all $configName patches"
            dependsOn(rebuildFilePatches, rebuildFeaturePatches)
        }
    }
}
