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
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*

class PaperweightCore : Plugin<Project> {
    override fun apply(target: Project) {
        checkJavaVersion()
        Git.checkForGit()

        val ext = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightCoreExtension::class, target)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(PARAM_MAPPINGS_CONFIG)
        target.configurations.create(REMAPPER_CONFIG)
        target.configurations.create(DECOMPILER_CONFIG)
        target.configurations.create(PAPERCLIP_CONFIG)

        if (target.providers.gradleProperty("paperweight.dev").orNull == "true") {
            target.tasks.register<CreateDiffOutput>("diff") {
                inputDir.convention(ext.paper.paperServerDir.map { it.dir("src/main/java") })
                val prop = target.providers.gradleProperty("paperweight.diff.output")
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

        target.tasks.register<PaperweightCorePrepareForDownstream>(PAPERWEIGHT_PREPARE_DOWNSTREAM) {
            dependsOn(tasks.applyPatches)
            vanillaJar.set(tasks.downloadServerJar.flatMap { it.outputJar })
            remappedJar.set(tasks.lineMapJar.flatMap { it.outputJar })
            decompiledJar.set(tasks.decompileJar.flatMap { it.outputJar })
            mcVersion.set(target.ext.minecraftVersion)
            mcLibrariesFile.set(tasks.extractFromBundler.flatMap { it.serverLibrariesTxt })
            mcLibrariesDir.set(tasks.extractFromBundler.flatMap { it.serverLibraryJars })
            mcLibrariesSourcesDir.set(tasks.downloadMcLibrariesSources.flatMap { it.outputDir })
            // TODO
            //mappings.set(tasks.patchMappings.flatMap { it.outputMappings })
            sourceMappings.set(tasks.generateMappings.flatMap { it.outputMappings })
            reobfPackagesToFix.set(ext.paper.reobfPackagesToFix)
            reobfMappingsPatch.set(ext.paper.reobfMappingsPatch)
            vanillaJarIncludes.set(ext.vanillaJarIncludes)
            paramMappingsUrl.set(ext.paramMappingsRepo)
            paramMappingsConfig.set(target.configurations.named(PARAM_MAPPINGS_CONFIG))
            atFile.set(tasks.mergeAdditionalAts.flatMap { it.outputFile })
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

            val serverProj = target.ext.serverProject.orNull ?: return@afterEvaluate
            serverProj.apply(plugin = "com.github.johnrengelman.shadow")
            val shadowJar = serverProj.tasks.named("shadowJar", Jar::class)

            // TODO
            //tasks.generateReobfMappings {
            //    inputJar.set(shadowJar.flatMap { it.archiveFile })
            //}

            val (_, reobfJar) = serverProj.setupServerProject(
                target,
                tasks.lineMapJar.flatMap { it.outputJar },
                tasks.decompileJar.flatMap { it.outputJar },
                ext.mcDevSourceDir.path,
                cache.resolve(SERVER_LIBRARIES_TXT),
                ext.paper.reobfPackagesToFix,
                // TODO
                //tasks.patchReobfMappings.flatMap { it.outputMappings }
                tasks.decompileJar.map { it.outputJar.get() }// dum
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
                // TODO
                //reobfMappingsFile.set(tasks.patchReobfMappings.flatMap { it.outputMappings })

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
                shadowJar,
                reobfJar,
                ext.minecraftVersion
            )
        }
    }
}
