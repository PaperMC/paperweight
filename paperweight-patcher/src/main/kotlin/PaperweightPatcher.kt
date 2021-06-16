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
import io.papermc.paperweight.tasks.GeneratePaperclipPatch
import io.papermc.paperweight.util.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
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

        target.configurations.create(Constants.REMAPPER_CONFIG)
        target.configurations.create(Constants.PAPERCLIP_CONFIG)

        val workDirProp = target.providers.gradleProperty(Constants.UPSTREAM_WORK_DIR_PROPERTY).forUseAtConfigurationTime()
        val dataFileProp = target.providers.gradleProperty(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM).forUseAtConfigurationTime()

        val applyPatches by target.tasks.registering { group = "paperweight" }
        val rebuildPatches by target.tasks.registering { group = "paperweight" }
        val downstreamData = target.tasks.register(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM)

        val upstreamDataTaskRef = AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>(null)

        patcher.upstreams.all {
            val taskPair = target.createUpstreamTask(this, patcher, workDirProp, dataFileProp, upstreamDataTaskRef)

            patchTasks.all {
                val createdPatchTask = target.createPatchTask(this, patcher, taskPair, applyPatches)
                downstreamData {
                    dependsOn(createdPatchTask)
                }
                target.rebuildPatchTask(this, rebuildPatches)
            }
        }

        target.afterEvaluate {
            val upstreamDataTask = upstreamDataTaskRef.get() ?: return@afterEvaluate
            val upstreamData = upstreamDataTask.flatMap {
                target.provider { readUpstreamData(it.dataFile) }
            }.orNull ?: return@afterEvaluate

            for (upstream in patcher.upstreams) {
                for (patchTask in upstream.patchTasks) {
                    patchTask.patchTask {
                        sourceMcDevJar.convention(upstreamData.decompiledJar)
                        mcLibrariesDir.convention(upstreamData.libSourceDir)
                    }
                }
            }

            val serverProj = patcher.serverProject.forUseAtConfigurationTime().orNull ?: return@afterEvaluate

            val reobfJar = serverProj.setupServerProject(
                target,
                upstreamData.remappedJar,
                upstreamData.libFile
            ) {
                mappingsFile.set(upstreamData.reobfMappings)
            } ?: return@afterEvaluate

            val generatePaperclipPatch by target.tasks.registering<GeneratePaperclipPatch> {
                originalJar.set(upstreamData.vanillaJar)
                patchedJar.set(reobfJar.flatMap { it.outputJar })
                mcVersion.set(upstreamData.mcVersion)
            }

            target.tasks.named("jar", Jar::class) {
                val paperclipConfig = target.configurations.named(Constants.PAPERCLIP_CONFIG)
                dependsOn(paperclipConfig, generatePaperclipPatch)

                val paperclipZip = target.zipTree(paperclipConfig.map { it.singleFile })
                from(paperclipZip) {
                    exclude("META-INF/MANIFEST.MF")
                }
                from(target.zipTree(generatePaperclipPatch.flatMap { it.outputZip }.get()))

                manifest.from(paperclipZip.matching { include("META-INF/MANIFEST.MF") }.singleFile)
            }
        }
    }

    private fun Project.createUpstreamTask(
        upstream: PatcherUpstream,
        ext: PaperweightPatcherExtension,
        workDirProp: Provider<String>,
        dataFileProp: Provider<String>,
        upstreamDataTaskRef: AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>
    ): Pair<TaskProvider<CheckoutRepo>?, TaskProvider<PaperweightPatcherUpstreamData>> {
        val workDirFromProp = layout.dir(workDirProp.map { File(it) }).orElse(ext.upstreamsDir)
        val dataFileFromProp = layout.file(dataFileProp.map { File(it) })

        val upstreamData = tasks.configureTask<PaperweightPatcherUpstreamData>(upstream.upstreamDataTaskName) {
            workDir.convention(workDirFromProp)
            if (dataFileFromProp.isPresent) {
                dataFile.convention(dataFileFromProp)
            } else {
                dataFile.convention(workDirFromProp.map { it.file("upstreamData${upstream.name.capitalize()}.json") })
            }
        }

        val cloneTask = (upstream as? RepoPatcherUpstream)?.let { repo ->
            val cloneTask = tasks.configureTask<CheckoutRepo>(repo.cloneTaskName) {
                repoName.convention(repo.name)
                url.convention(repo.url)
                ref.convention(repo.ref)

                workDir.convention(workDirFromProp)
            }

            upstreamData {
                dependsOn(cloneTask)
                projectDir.convention(cloneTask.flatMap { it.outputDir })
            }

            return@let cloneTask
        }

        if (upstream.useForUpstreamData.getOrElse(false)) {
            upstreamDataTaskRef.set(upstreamData)
        } else {
            upstreamDataTaskRef.compareAndSet(null, upstreamData)
        }

        return cloneTask to upstreamData
    }

    private fun Project.createPatchTask(
        config: PatchTaskConfig,
        ext: PaperweightPatcherExtension,
        upstreamTaskPair: Pair<TaskProvider<CheckoutRepo>?, TaskProvider<PaperweightPatcherUpstreamData>>,
        applyPatches: TaskProvider<Task>
    ): TaskProvider<SimpleApplyGitPatches> {
        val project = this
        val patchTask = tasks.configureTask<SimpleApplyGitPatches>(config.patchTaskName) {
            group = "paperweight"
            val (cloneTask, upstreamDataTask) = upstreamTaskPair
            dependsOn(upstreamDataTask)

            if (cloneTask != null) {
                sourceDir.convention(cloneTask.flatMap { it.outputDir.dir(config.sourceDirPath) })
            } else {
                sourceDir.convention(config.sourceDir)
            }

            patchDir.convention(config.patchDir.flatMap { provider { if (it.path.exists()) it else null } })
            outputDir.convention(config.outputDir)

            importMcDev.convention(config.importMcDev)
            libraryImports.convention(ext.libraryImports.fileExists(project))
            mcdevImports.convention(ext.mcdevImports.fileExists(project))
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

            patchDir.convention(config.patchDir)
            inputDir.convention(config.outputDir)
        }

        rebuildPatches {
            dependsOn(rebuildTask)
        }

        return rebuildTask
    }
}
