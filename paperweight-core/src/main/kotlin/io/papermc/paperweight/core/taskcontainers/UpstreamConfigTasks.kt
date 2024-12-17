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

import codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.core.extension.UpstreamConfig
import io.papermc.paperweight.core.tasks.FilterRepo
import io.papermc.paperweight.core.tasks.RunNestedBuild
import io.papermc.paperweight.core.tasks.patching.ApplySingleFilePatches
import io.papermc.paperweight.core.tasks.patching.RebuildSingleFilePatches
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

class UpstreamConfigTasks(
    private val target: Project,
    private val upstreamCfg: UpstreamConfig,
    private val upstreamDir: Provider<Directory>,
    private val readOnly: Boolean,
    private val taskGroup: String,
    private val gitFilePatches: Provider<Boolean>,
    private val setupUpstream: TaskProvider<out RunNestedBuild>?,
    private val upstreamTasks: UpstreamConfigTasks?,
) {
    private fun ApplySingleFilePatches.configureApplySingleFilePatches() {
        group = taskGroup
        upstream.set(upstreamDir)
        val patches = upstreamCfg.singleFilePatchSets.map {
            it.map { cfg ->
                ApplySingleFilePatches.Patch.patch(target.objects, upstream) {
                    path = cfg.path
                    patchFile = layout.file(
                        cfg.patchFile.flatMap { f ->
                            if (f.path.exists()) target.providers.provider { f.asFile } else target.providers.provider { null }
                        }
                    )
                    outputFile = cfg.outputFile
                }
            }
        }
        this.patches.set(patches)
        if (!upstreamCfg.paper.get()) {
            setupUpstream?.let { dependsOn(it) }
        }
    }

    val applySingleFilePatches = if (upstreamCfg.singleFilePatches) {
        target.tasks.register<ApplySingleFilePatches>("apply${upstreamCfg.name.capitalized()}SingleFilePatches") {
            configureApplySingleFilePatches()
        }
    } else {
        null
    }

    val applySingleFilePatchesFuzzy = if (upstreamCfg.singleFilePatches) {
        target.tasks.register<ApplySingleFilePatches>("apply${upstreamCfg.name.capitalized()}SingleFilePatchesFuzzy") {
            configureApplySingleFilePatches()
            mode.set(PatchMode.FUZZY)
        }
    } else {
        null
    }

    val rebuildSingleFilePatches = if (upstreamCfg.singleFilePatches) {
        target.tasks.register<RebuildSingleFilePatches>("rebuild${upstreamCfg.name.capitalized()}SingleFilePatches") {
            group = taskGroup
            upstream.set(upstreamDir)
            val patches = upstreamCfg.singleFilePatchSets.map {
                it.map { cfg ->
                    val p = objects.newInstance<RebuildSingleFilePatches.Patch>()
                    p.path = cfg.path
                    p.patchFile = cfg.patchFile
                    p.outputFile = cfg.outputFile
                    p
                }
            }
            this.patches.set(patches)
            if (!upstreamCfg.paper.get()) {
                setupUpstream?.let { dependsOn(it) }
            }
        }
    } else {
        null
    }

    val patchingTasks: Map<UpstreamConfig.DirectoryPatchSet, PatchingTasks> = upstreamCfg.directoryPatchSets
        .associateWith { cfg -> makePatchingTasks(cfg) }

    private fun makePatchingTasks(cfg: UpstreamConfig.DirectoryPatchSet): PatchingTasks {
        val base = if (cfg is UpstreamConfig.RepoPatchSet) {
            createBaseFromRepo(cfg)
        } else {
            createBaseFromDirectoryInRepo(cfg)
        }

        return PatchingTasks(
            target,
            cfg.name,
            taskGroup,
            readOnly,
            cfg.filePatchDir,
            cfg.rejectsDir,
            cfg.featurePatchDir,
            base,
            gitFilePatches,
            cfg.outputDir.path,
        )
    }

    private fun createBaseFromRepo(cfg: UpstreamConfig.DirectoryPatchSet): Provider<Directory> {
        val task = target.tasks.register<FilterRepo>(
            "checkout${cfg.name.capitalized()}From${upstreamCfg.name.capitalized()}"
        ) {
            group = taskGroup
            if (cfg is UpstreamConfig.RepoPatchSet && cfg.upstreamRepo.isPresent) {
                val upstreamTasks = requireNotNull(upstreamTasks) { "Upstream tasks not present when expected" }
                val patchingTasksForDir = requireNotNull(upstreamTasks.patchingTasks[cfg.upstreamRepo.get()]) {
                    "Patching tasks not present upstream for ${cfg.upstreamRepo.get().name}"
                }
                val input = patchingTasksForDir.applyFeaturePatches.flatMap { it.repo }
                inputDir.set(input)
            } else {
                inputDir.set(upstreamDir.flatMap { it.dir(cfg.upstreamPath) })
            }
            excludes.set(cfg.excludes)
            setupUpstream?.let { dependsOn(it) }
        }
        return task.flatMap { it.outputDir }
    }

    private fun createBaseFromDirectoryInRepo(cfg: UpstreamConfig.DirectoryPatchSet): Provider<Directory> {
        val task = target.tasks.register<FilterRepo>(
            "filter${cfg.name.capitalized()}From${upstreamCfg.name.capitalized()}"
        ) {
            group = taskGroup
            inputDir.set(upstreamDir.flatMap { it.dir(cfg.upstreamPath) })
            gitDir.set(upstreamDir.map { it.dir(".git") })
            excludes.set(cfg.excludes)
        }
        return task.flatMap { it.outputDir }
    }

    fun setupAggregateTasks(namePart: String) {
        val applyFile = target.tasks.register("apply${namePart}FilePatches") {
            group = taskGroup
            patchingTasks.values.forEach { t ->
                dependsOn(t.applyFilePatches)
            }
        }
        val applyFeature = target.tasks.register("apply${namePart}FeaturePatches") {
            group = taskGroup
            patchingTasks.values.forEach { t ->
                dependsOn(t.applyFeaturePatches)
            }
        }
        val rebuildFile = target.tasks.register("rebuild${namePart}FilePatches") {
            group = taskGroup
            patchingTasks.values.forEach { t ->
                dependsOn(t.rebuildFilePatchesName)
            }
        }
        val rebuildFeature = target.tasks.register("rebuild${namePart}FeaturePatches") {
            group = taskGroup
            patchingTasks.values.forEach { t ->
                dependsOn(t.rebuildFeaturePatchesName)
            }
        }
        val apply = target.tasks.register("apply${namePart}Patches") {
            group = taskGroup
            applySingleFilePatches?.let { t -> dependsOn(t) }
            patchingTasks.values.forEach { t ->
                dependsOn(t.applyPatches)
            }
        }
        val rebuild = target.tasks.register("rebuild${namePart}Patches") {
            rebuildSingleFilePatches?.let { t -> dependsOn(t) }
            group = taskGroup
            patchingTasks.values.forEach { t ->
                dependsOn(t.rebuildPatchesName)
            }
        }
    }
}
