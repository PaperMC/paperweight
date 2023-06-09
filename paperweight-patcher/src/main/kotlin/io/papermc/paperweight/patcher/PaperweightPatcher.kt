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

package io.papermc.paperweight.patcher

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.patcher.tasks.*
import io.papermc.paperweight.patcher.upstream.PatchTaskConfig
import io.papermc.paperweight.patcher.upstream.PatcherUpstream
import io.papermc.paperweight.patcher.upstream.RepoPatcherUpstream
import io.papermc.paperweight.taskcontainers.BundlerJarTasks
import io.papermc.paperweight.taskcontainers.DevBundleTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.registering

class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        checkJavaVersion()
        Git.checkForGit()

        val patcher = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightPatcherExtension::class, target)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(DECOMPILER_CONFIG)
        target.configurations.create(REMAPPER_CONFIG)
        target.configurations.create(PAPERCLIP_CONFIG)

        val workDirProp = target.providers.gradleProperty(UPSTREAM_WORK_DIR_PROPERTY)
        val dataFileProp = target.providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY)

        val applyPatches by target.tasks.registering { group = "paperweight" }
        val rebuildPatches by target.tasks.registering { group = "paperweight" }
        val generateReobfMappings by target.tasks.registering(GenerateReobfMappings::class)

        val mergeReobfMappingsPatches by target.tasks.registering<PatchMappings> {
            patch.set(patcher.reobfMappingsPatch.fileExists(target))
            outputMappings.convention(defaultOutput("tiny"))

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)
        }

        val patchReobfMappings by target.tasks.registering<PatchMappings> {
            inputMappings.set(generateReobfMappings.flatMap { it.reobfMappings })
            patch.set(mergeReobfMappingsPatches.flatMap { it.outputMappings })
            outputMappings.set(target.layout.cache.resolve(PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)
        }

        val prepareForDownstream = target.tasks.register<PaperweightPatcherPrepareForDownstream>(PAPERWEIGHT_PREPARE_DOWNSTREAM) {
            dataFile.fileProvider(dataFileProp.map { File(it) })
            reobfMappingsPatch.set(mergeReobfMappingsPatches.flatMap { it.outputMappings })
        }

        val upstreamDataTaskRef = AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>(null)

        patcher.upstreams.all {
            val taskPair = target.createUpstreamTask(this, patcher, workDirProp, upstreamDataTaskRef)

            patchTasks.all {
                val createdPatchTask = target.createPatchTask(this, patcher, taskPair, applyPatches)
                prepareForDownstream {
                    dependsOn(createdPatchTask)
                }
                target.rebuildPatchTask(this, rebuildPatches)
            }
        }

        val devBundleTasks = DevBundleTasks(target)

        val bundlerJarTasks = BundlerJarTasks(
            target,
            patcher.bundlerJarName,
            patcher.mainClass
        )

        target.afterEvaluate {
            target.repositories {
                maven(patcher.remapRepo) {
                    name = REMAPPER_REPO_NAME
                    content { onlyForConfigurations(REMAPPER_CONFIG) }
                }
                maven(patcher.decompileRepo) {
                    name = DECOMPILER_REPO_NAME
                    content { onlyForConfigurations(DECOMPILER_CONFIG) }
                }
            }

            val upstreamDataTask = upstreamDataTaskRef.get() ?: return@afterEvaluate
            val upstreamData = upstreamDataTask.map { readUpstreamData(it.dataFile) }

            mergeReobfMappingsPatches {
                inputMappings.pathProvider(upstreamData.map { it.reobfMappingsPatch })
            }
            val mergedReobfPackagesToFix = upstreamData.zip(patcher.reobfPackagesToFix) { data, pkgs ->
                data.reobfPackagesToFix + pkgs
            }

            prepareForDownstream {
                upstreamDataFile.set(upstreamDataTask.flatMap { it.dataFile })
                reobfPackagesToFix.set(mergedReobfPackagesToFix)
            }

            for (upstream in patcher.upstreams) {
                for (patchTask in upstream.patchTasks) {
                    patchTask.patchTask {
                        sourceMcDevJar.convention(target, upstreamData.map { it.decompiledJar })
                        mcLibrariesDir.convention(target, upstreamData.map { it.libSourceDir })
                    }
                }
            }

            val serverProj = patcher.serverProject.orNull ?: return@afterEvaluate
            serverProj.apply(plugin = "com.github.johnrengelman.shadow")
            val shadowJar = serverProj.tasks.named("shadowJar", Jar::class)

            generateReobfMappings {
                inputMappings.pathProvider(upstreamData.map { it.mappings })
                sourceMappings.pathProvider(upstreamData.map { it.sourceMappings })
                inputJar.set(shadowJar.flatMap { it.archiveFile })

                reobfMappings.set(target.layout.cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
            }

            val (_, reobfJar) = serverProj.setupServerProject(
                target,
                upstreamData.map { it.remappedJar },
                upstreamData.map { it.decompiledJar },
                patcher.mcDevSourceDir.path,
                upstreamData.map { it.libFile },
                mergedReobfPackagesToFix,
                patchReobfMappings.flatMap { it.outputMappings }
            ) ?: return@afterEvaluate

            devBundleTasks.configure(
                patcher.serverProject.get(),
                patcher.bundlerJarName.get(),
                patcher.mainClass,
                upstreamData.map { it.mcVersion },
                upstreamData.map { it.decompiledJar },
                upstreamData.map { it.serverLibrariesTxt },
                upstreamData.map { it.serverLibrariesList },
                upstreamData.map { it.vanillaJar },
                upstreamData.map { it.accessTransform },
                upstreamData.map { it.bundlerVersionJson }.convertToFileProvider(layout, providers)
            ) {
                vanillaJarIncludes.set(upstreamData.map { it.vanillaIncludes })
                reobfMappingsFile.set(patchReobfMappings.flatMap { it.outputMappings })

                paramMappingsCoordinates.set(upstreamData.map { it.paramMappings.coordinates.single() })
                paramMappingsUrl.set(upstreamData.map { it.paramMappings.url })
            }
            devBundleTasks.configureAfterEvaluate()

            bundlerJarTasks.configureBundlerTasks(
                upstreamData.map { it.bundlerVersionJson }.convertToFileProvider(target.layout, target.providers),
                upstreamData.map { it.serverLibrariesList }.convertToFileProvider(target.layout, target.providers),
                upstreamData.map { it.vanillaJar }.convertToFileProvider(target.layout, target.providers),
                shadowJar,
                reobfJar,
                upstreamData.map { it.mcVersion }
            )
        }
    }

    private fun Project.createUpstreamTask(
        upstream: PatcherUpstream,
        ext: PaperweightPatcherExtension,
        workDirProp: Provider<String>,
        upstreamDataTaskRef: AtomicReference<TaskProvider<PaperweightPatcherUpstreamData>>
    ): Pair<TaskProvider<CheckoutRepo>?, TaskProvider<PaperweightPatcherUpstreamData>> {
        val workDirFromProp = layout.dir(workDirProp.map { File(it) }).orElse(ext.upstreamsDir)

        val upstreamData = tasks.configureTask<PaperweightPatcherUpstreamData>(upstream.upstreamDataTaskName) {
            workDir.convention(workDirFromProp)
            dataFile.convention(workDirFromProp.map { it.file("upstreamData${upstream.name.capitalize()}.json") })
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
    ): TaskProvider<PatcherApplyGitPatches> {
        val project = this
        val patchTask = tasks.configureTask<PatcherApplyGitPatches>(config.patchTaskName) {
            group = "paperweight"

            if (isBaseExecution) {
                doNotTrackState("$name should always run when requested as part of the base execution.")
            }
            printOutput.set(isBaseExecution)

            val (cloneTask, upstreamDataTask) = upstreamTaskPair
            dependsOn(upstreamDataTask)

            if (cloneTask != null) {
                upstreamDir.convention(cloneTask.flatMap { it.outputDir.dir(config.upstreamDirPath) })
            } else {
                upstreamDir.convention(config.upstreamDir)
            }

            patchDir.convention(config.patchDir.fileExists(project))
            outputDir.convention(config.outputDir)
            mcDevSources.set(ext.mcDevSourceDir)

            bareDirectory.convention(config.isBareDirectory)
            importMcDev.convention(config.importMcDev)
            devImports.convention(ext.devImports.fileExists(project))
        }

        applyPatches {
            dependsOn(patchTask)
        }

        return patchTask
    }

    private fun Project.rebuildPatchTask(
        config: PatchTaskConfig,
        rebuildPatches: TaskProvider<Task>
    ): TaskProvider<RebuildGitPatches> {
        val rebuildTask = tasks.configureTask<RebuildGitPatches>(config.rebuildTaskName) {
            group = "paperweight"

            patchDir.convention(config.patchDir)
            inputDir.convention(config.outputDir)
            baseRef.convention("base")
        }

        rebuildPatches {
            dependsOn(rebuildTask)
        }

        return rebuildTask
    }
}
