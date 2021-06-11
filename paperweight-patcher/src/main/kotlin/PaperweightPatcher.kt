/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.patcher

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.patcher.tasks.CheckoutRepo
import io.papermc.paperweight.patcher.tasks.PaperweightPatcherUpstreamData
import io.papermc.paperweight.patcher.tasks.SimpleApplyGitPatches
import io.papermc.paperweight.patcher.upstream.PatchTaskConfig
import io.papermc.paperweight.patcher.upstream.PatcherUpstream
import io.papermc.paperweight.patcher.upstream.RepoPatcherUpstream
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.configureTask
import io.papermc.paperweight.util.readUpstreamData
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        val patcher = target.extensions.create(Constants.EXTENSION, PaperweightPatcherExtension::class)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "Paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(Constants.PAPERCLIP_CONFIG)

        val workDirProp = target.providers.gradleProperty(Constants.UPSTREAM_WORK_DIR_PROPERTY).forUseAtConfigurationTime()
        val dataFileProp = target.providers.gradleProperty(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM).forUseAtConfigurationTime()

        val applyPatches by target.tasks.registering
        val downstreamData = target.tasks.register(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM)

        val upstreamDataTaskRef = AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>(null)

        patcher.upstreams.all {
            val taskPair = target.createUpstreamTask(this, patcher, workDirProp, dataFileProp, upstreamDataTaskRef)

            patchTasks.all {
                val createdPatchTask = target.createPatchTask(this, taskPair, applyPatches)
                downstreamData {
                    dependsOn(createdPatchTask)
                }
            }
        }

        target.afterEvaluate {
            val upstreamDataTask = upstreamDataTaskRef.get() ?: return@afterEvaluate
            val upstreamData = upstreamDataTask.map { readUpstreamData(it.dataFile) }

            // Create build tasks which requires upstream data
        }
    }

    private fun Project.createUpstreamTask(
        upstream: PatcherUpstream,
        ext: PaperweightPatcherExtension,
        workDirProp: Provider<String>,
        dataFileProp: Provider<String>,
        upstreamDataTaskRef: AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>
    ): Pair<TaskProvider<CheckoutRepo>, TaskProvider<PaperweightPatcherUpstreamData>>? {
        return (upstream as? RepoPatcherUpstream)?.let { repo ->
            val workDirFromProp = layout.dir(workDirProp.map { File(it) }).orElse(ext.upstreamsDir)
            val dataFileFromProp = layout.file(dataFileProp.map { File(it) })

            val cloneTask = tasks.configureTask<CheckoutRepo>(repo.cloneTaskName) {
                repoName.set(repo.name)
                url.set(repo.url)
                ref.set(repo.ref)

                workDir.set(workDirFromProp)
            }

            val upstreamData = tasks.configureTask<PaperweightPatcherUpstreamData>(repo.upstreamDataTaskName) {
                dependsOn(cloneTask)
                projectDir.set(cloneTask.flatMap { it.outputDir })
                workDir.set(workDirFromProp)
                if (dataFileFromProp.isPresent) {
                    dataFile.set(dataFileFromProp)
                } else {
                    dataFile.set(workDirFromProp.map { it.file("upstreamData${repo.name.capitalize()}.json") })
                }
            }

            if (repo.useForUpstreamData.get()) {
                upstreamDataTaskRef.set(upstreamData)
            } else {
                upstreamDataTaskRef.compareAndSet(null, upstreamData)
            }

            return@let cloneTask to upstreamData
        }
    }

    private fun Project.createPatchTask(
        config: PatchTaskConfig,
        upstreamTaskPair: Pair<TaskProvider<CheckoutRepo>, TaskProvider<PaperweightPatcherUpstreamData>>?,
        applyPatches: TaskProvider<Task>
    ): TaskProvider<SimpleApplyGitPatches> {
        val patchTask = tasks.configureTask<SimpleApplyGitPatches>(config.patchTaskName) {
            if (upstreamTaskPair != null) {
                val (cloneTask, upstreamDataTask) = upstreamTaskPair
                dependsOn(upstreamDataTask)
                sourceDir.set(cloneTask.flatMap { it.outputDir.dir(config.sourceDirPath) })
            } else {
                sourceDir.set(config.sourceDir)
            }

            patchDir.set(config.patchDir)
            outputDir.set(config.outputDir)
        }

        applyPatches {
            dependsOn(patchTask)
        }

        return patchTask
    }
}
