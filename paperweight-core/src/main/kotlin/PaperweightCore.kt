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

package io.papermc.paperweight.core

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.core.taskcontainers.AllTasks
import io.papermc.paperweight.core.tasks.PaperweightCorePrepareForDownstream
import io.papermc.paperweight.taskcontainers.BundlerJarTasks
import io.papermc.paperweight.taskcontainers.DevBundleTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.patchremap.RemapPatches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*

class PaperweightCore : Plugin<Project> {
    override fun apply(target: Project) {
        Git.checkForGit()

        val ext = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightCoreExtension::class, target)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        // Make sure the submodules are initialized, since there are files there
        // which are required for configuration
        target.layout.initSubmodules()

        target.configurations.create(PARAM_MAPPINGS_CONFIG)
        target.configurations.create(REMAPPER_CONFIG)
        target.configurations.create(DECOMPILER_CONFIG)
        target.configurations.create(PAPERCLIP_CONFIG)

        if (target.providers.gradleProperty("paperweight.dev").forUseAtConfigurationTime().orNull == "true") {
            target.tasks.register<CreateDiffOutput>("diff") {
                inputDir.convention(ext.paper.paperServerDir.map { it.dir("src/main/java") })
                val prop = target.providers.gradleProperty("paperweight.diff.output").forUseAtConfigurationTime()
                if (prop.isPresent) {
                    baseDir.convention(target.layout.projectDirectory.dir(prop))
                }
            }
        }

        val tasks = AllTasks(target)

        val devBundleTasks = DevBundleTasks(target)

        val bundlerJarTasks = BundlerJarTasks(
            target,
            ext.bundlerJarName,
            ext.mainClass
        )

        target.createPatchRemapTask(tasks)

        target.tasks.register<PaperweightCorePrepareForDownstream>(PAPERWEIGHT_PREPARE_DOWNSTREAM) {
            dependsOn(tasks.applyPatches)
            vanillaJar.set(tasks.downloadServerJar.flatMap { it.outputJar })
            remappedJar.set(tasks.lineMapJar.flatMap { it.outputJar })
            decompiledJar.set(tasks.decompileJar.flatMap { it.outputJar })
            mcVersion.set(target.ext.minecraftVersion)
            mcLibrariesFile.set(tasks.extractFromBundler.flatMap { it.serverLibrariesTxt })
            mcLibrariesDir.set(tasks.extractFromBundler.flatMap { it.serverLibraryJars })
            mcLibrariesSourcesDir.set(tasks.downloadMcLibrariesSources.flatMap { it.outputDir })
            spigotLibrariesSourcesDir.set(tasks.downloadSpigotDependencies.flatMap { it.outputSourcesDir })
            mappings.set(tasks.patchMappings.flatMap { it.outputMappings })
            notchToSpigotMappings.set(tasks.generateSpigotMappings.flatMap { it.notchToSpigotMappings })
            sourceMappings.set(tasks.generateMappings.flatMap { it.outputMappings })
            reobfPackagesToFix.set(ext.paper.reobfPackagesToFix)
            reobfMappingsPatch.set(ext.paper.reobfMappingsPatch)
            vanillaJarIncludes.set(ext.vanillaJarIncludes)
            paramMappingsUrl.set(ext.paramMappingsRepo)
            paramMappingsConfig.set(target.configurations.named(PARAM_MAPPINGS_CONFIG))
            atFile.set(tasks.mergeAdditionalAts.flatMap { it.outputFile })
            spigotRecompiledClasses.set(tasks.remapSpigotSources.flatMap { it.spigotRecompiledClasses })
            bundlerVersionJson.set(tasks.extractFromBundler.flatMap { it.versionJson })
            serverLibrariesTxt.set(tasks.extractFromBundler.flatMap { it.serverLibrariesTxt })
            serverLibrariesList.set(tasks.extractFromBundler.flatMap { it.serverLibrariesList })

            dataFile.set(
                target.layout.file(
                    providers.gradleProperty(PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY).map { File(it) }
                )
            )
        }

        target.afterEvaluate {
            target.repositories {
                maven(ext.paramMappingsRepo) {
                    name = PARAM_MAPPINGS_REPO_NAME
                    content { onlyForConfigurations(PARAM_MAPPINGS_CONFIG) }
                }
                maven(ext.remapRepo) {
                    name = REMAPPER_REPO_NAME
                    content { onlyForConfigurations(REMAPPER_CONFIG) }
                }
                maven(ext.decompileRepo) {
                    name = DECOMPILER_REPO_NAME
                    content { onlyForConfigurations(DECOMPILER_CONFIG) }
                }
            }

            // Setup the server jar
            val cache = target.layout.cache

            val serverProj = target.ext.serverProject.forUseAtConfigurationTime().orNull ?: return@afterEvaluate
            serverProj.apply(plugin = "com.github.johnrengelman.shadow")
            val shadowJar = serverProj.tasks.named("shadowJar", Jar::class)

            tasks.generateReobfMappings {
                inputJar.set(shadowJar.flatMap { it.archiveFile })
            }

            val (_, reobfJar) = serverProj.setupServerProject(
                target,
                tasks.lineMapJar.flatMap { it.outputJar },
                tasks.decompileJar.flatMap { it.outputJar },
                ext.mcDevSourceDir.path,
                cache.resolve(SERVER_LIBRARIES_TXT),
                ext.paper.reobfPackagesToFix,
                tasks.patchReobfMappings.flatMap { it.outputMappings }
            ) ?: return@afterEvaluate

            devBundleTasks.configure(
                ext.serverProject.get(),
                ext.bundlerJarName.get(),
                ext.mainClass,
                ext.minecraftVersion,
                tasks.decompileJar.map { it.outputJar.path },
                tasks.extractFromBundler.map { it.serverLibrariesTxt.path },
                tasks.extractFromBundler.map { it.serverLibrariesList.path },
                tasks.downloadServerJar.map { it.outputJar.path },
                tasks.mergeAdditionalAts.map { it.outputFile.path },
                tasks.extractFromBundler.map { it.versionJson.path }.convertToFileProvider(layout, providers)
            ) {
                vanillaJarIncludes.set(ext.vanillaJarIncludes)
                reobfMappingsFile.set(tasks.patchReobfMappings.flatMap { it.outputMappings })

                paramMappingsCoordinates.set(
                    target.provider {
                        determineArtifactCoordinates(target.configurations.getByName(PARAM_MAPPINGS_CONFIG)).single()
                    }
                )
                paramMappingsUrl.set(ext.paramMappingsRepo)
            }
            devBundleTasks.configureAfterEvaluate()

            bundlerJarTasks.configureBundlerTasks(
                tasks.extractFromBundler.flatMap { it.versionJson },
                tasks.extractFromBundler.flatMap { it.serverLibrariesList },
                tasks.downloadServerJar.flatMap { it.outputJar },
                serverProj,
                shadowJar,
                reobfJar,
                ext.minecraftVersion
            )
        }
    }

    private fun Project.createPatchRemapTask(allTasks: AllTasks) {
        val extension: PaperweightCoreExtension = ext

        /*
         * To ease the waiting time for debugging this task, all of the task dependencies have been removed (notice all
         * of those .get() calls). This means when you make changes to paperweight Gradle won't know that this task
         * technically depends on the output of all of those other tasks.
         *
         * In order to run all of the other necessary tasks before running this task in order to setup the inputs, run:
         *
         *   ./gradlew patchPaper applyVanillaSrgAt
         *
         * Then you should be able to run `./gradlew remapPatches` without having to worry about all of the other tasks
         * running whenever you make changes to paperweight.
         */

        @Suppress("UNUSED_VARIABLE")
        val remapPatches: TaskProvider<RemapPatches> by tasks.registering<RemapPatches> {
            group = "paperweight"
            description = "NOT FOR TYPICAL USE: Attempt to remap Paper's patches from Spigot mappings to Mojang mappings."

            inputPatchDir.set(extension.paper.unmappedSpigotServerPatchDir)
            apiPatchDir.set(extension.paper.spigotApiPatchDir)

            mappingsFile.set(allTasks.patchMappings.flatMap { it.outputMappings }.get())
            ats.set(allTasks.remapSpigotSources.flatMap { it.generatedAt }.get())

            // Pull in as many jars as possible to reduce the possibility of type bindings not resolving
            classpathJars.from(allTasks.applyMergedAt.flatMap { it.outputJar }.get()) // final remapped jar
            classpathJars.from(allTasks.remapSpigotSources.flatMap { it.vanillaRemappedSpigotJar }.get()) // Spigot remapped jar
            classpathJars.from(allTasks.extractFromBundler.flatMap { it.serverJar }.get()) // pure vanilla jar

            spigotApiDir.set(allTasks.patchSpigotApi.flatMap { it.outputDir }.get())
            spigotServerDir.set(allTasks.patchSpigotServer.flatMap { it.outputDir }.get())
            spigotDecompJar.set(allTasks.spigotDecompileJar.flatMap { it.outputJar }.get())

            // library class imports
            mcLibrarySourcesDir.set(allTasks.downloadMcLibrariesSources.flatMap { it.outputDir }.get())
            devImports.set(extension.paper.devImports)

            outputPatchDir.set(extension.paper.remappedSpigotServerPatchDir)
        }
    }
}
