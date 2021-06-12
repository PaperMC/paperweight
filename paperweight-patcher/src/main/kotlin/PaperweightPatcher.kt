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
import io.papermc.paperweight.patcher.tasks.SimpleRebuildGitPatches
import io.papermc.paperweight.patcher.upstream.PatchTaskConfig
import io.papermc.paperweight.patcher.upstream.PatcherUpstream
import io.papermc.paperweight.patcher.upstream.RepoPatcherUpstream
import io.papermc.paperweight.util.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.forEachLine
import kotlin.io.path.isRegularFile
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.registering

class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        val patcher = target.extensions.create(Constants.EXTENSION, PaperweightPatcherExtension::class)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(Constants.PAPERCLIP_CONFIG)

        val workDirProp = target.providers.gradleProperty(Constants.UPSTREAM_WORK_DIR_PROPERTY).forUseAtConfigurationTime()
        val dataFileProp = target.providers.gradleProperty(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM).forUseAtConfigurationTime()

        val applyPatches by target.tasks.registering { group = "paperweight"}
        val rebuildPatches by target.tasks.registering { group = "paperweight"}
        val downstreamData = target.tasks.register(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM)

        val upstreamDataTaskRef = AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>(null)

        patcher.upstreams.all {
            val taskPair = target.createUpstreamTask(this, patcher, workDirProp, dataFileProp, upstreamDataTaskRef)

            patchTasks.all {
                val createdPatchTask = target.createPatchTask(this, taskPair, applyPatches)
                downstreamData {
                    dependsOn(createdPatchTask)
                }
                target.rebuildPatchTask(this, rebuildPatches)
            }
        }

        target.afterEvaluate {
            val upstreamDataTask = upstreamDataTaskRef.get() ?: return@afterEvaluate
            if (!upstreamDataTask.isPresent || !upstreamDataTask.get().dataFile.path.isRegularFile()) {
                println("Can't add dependencies, no upstream data")
                return@afterEvaluate
            }
            val upstreamData = upstreamDataTask.map {readUpstreamData(it.dataFile) }

            patcher.serverProject.apply {
                plugins.apply("java")
                dependencies.apply {
                    val remappedJar = upstreamData.orNull?.remappedJar
                    if (remappedJar != null && remappedJar.exists()) {
                        println("adding remapped jar " + remappedJar)
                        add("implementation", target.files(remappedJar))
                    } else {
                        println("Can't add remapped jar to the dependencies, file not found or not part of upstream data")
                    }

                    val libsFile = upstreamData.orNull?.libFile
                    if (libsFile != null && libsFile.exists()) {
                        libsFile.forEachLine { line ->
                            add("implementation", line)
                        }
                    } else {
                        println("Can't add libs to the dependencies, libs file not found or not part of upstream data")
                    }
                }
                apply(plugin = "com.github.johnrengelman.shadow")
            }

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

            if (repo.useForUpstreamData.getOrElse(false)) {
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
            group = "paperweight"
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

    private fun Project.rebuildPatchTask(
            config: PatchTaskConfig,
            rebuildPatches: TaskProvider<Task>
    ): TaskProvider<SimpleRebuildGitPatches> {
        val rebuildTask = tasks.configureTask<SimpleRebuildGitPatches>(config.rebuildTaskName) {
            group = "paperweight"

            patchDir.set(config.patchDir)
            inputDir.set(config.outputDir)
        }

        rebuildPatches {
            dependsOn(rebuildTask)
        }

        return rebuildTask
    }
}
