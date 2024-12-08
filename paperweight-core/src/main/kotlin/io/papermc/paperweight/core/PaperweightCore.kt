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
import io.papermc.paperweight.core.taskcontainers.SoftSpoonTasks
import io.papermc.paperweight.taskcontainers.BundlerJarTasks
import io.papermc.paperweight.taskcontainers.DevBundleTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

class PaperweightCore : Plugin<Project> {
    companion object {
        private val logger = Logging.getLogger(PaperweightCore::class.java)
    }

    override fun apply(target: Project) {
        checkJavaVersion()
        Git.checkForGit()
        printId<PaperweightCore>("paperweight-core", target.gradle)

        val ext = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightCoreExtension::class, target)

        target.gradle.sharedServices.registerIfAbsent(DOWNLOAD_SERVICE_NAME, DownloadService::class) {
            parameters.projectPath.set(target.projectDir)
        }

        target.tasks.register<Delete>("cleanCache") {
            group = "paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(REMAPPER_CONFIG)
        target.configurations.create(PAPERCLIP_CONFIG)
        target.configurations.create(MACHE_CONFIG) {
            attributes.attribute(MacheOutput.ATTRIBUTE, target.objects.named(MacheOutput.ZIP))
        }

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

        val softSpoonTasks = SoftSpoonTasks(target, tasks)

        val jar = target.tasks.named("jar", AbstractArchiveTask::class)
        tasks.generateReobfMappings {
            inputJar.set(jar.flatMap { it.archiveFile })
        }
        tasks.generateRelocatedReobfMappings {
            inputJar.set(jar.flatMap { it.archiveFile })
        }
        val (includeMappings, reobfJar) = target.createBuildTasks(
            ext.spigot.packageVersion,
            ext.paper.reobfPackagesToFix,
            tasks.generateRelocatedReobfMappings
        )
        bundlerJarTasks.configureBundlerTasks(
            tasks.extractFromBundler.flatMap { it.versionJson },
            tasks.extractFromBundler.flatMap { it.serverLibrariesList },
            tasks.downloadServerJar.flatMap { it.outputJar },
            includeMappings.flatMap { it.outputJar },
            reobfJar,
            ext.minecraftVersion
        )

        /*
        target.tasks.register<PaperweightCorePrepareForDownstream>(PAPERWEIGHT_PREPARE_DOWNSTREAM) {
            dependsOn(tasks.applyPatchesLegacy)
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
         */

        target.afterEvaluate {
            target.repositories {
                /* TODO
                maven(ext.remapRepo) {
                    name = REMAPPER_REPO_NAME
                    content { onlyForConfigurations(REMAPPER_CONFIG) }
                }
                 */
                maven(ext.macheRepo) {
                    name = MACHE_REPO_NAME
                    content { onlyForConfigurations(MACHE_CONFIG) }
                }
            }

            softSpoonTasks.afterEvaluate()

            /*
            // Setup the server jar
            val cache = target.layout.cache

            val serverProj = target.ext.serverProject.orNull ?: return@afterEvaluate
            val serverJar = serverProj.tasks.register("serverJar", Zip::class)

            tasks.generateReobfMappings {
                inputJar.set(serverJar.flatMap { it.archiveFile })
            }
            tasks.generateRelocatedReobfMappings {
                inputJar.set(serverJar.flatMap { it.archiveFile })
            }

            val (includeMappings, reobfJar) = serverProj.setupServerProject(
                target,
                tasks.lineMapJar.flatMap { it.outputJar },
                tasks.decompileJar.flatMap { it.outputJar },
                ext.mcDevSourceDir.path,
                cache.resolve(SERVER_LIBRARIES_TXT),
                ext.paper.reobfPackagesToFix,
                tasks.generateRelocatedReobfMappings,
                serverJar
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
                reobfMappingsFile.set(tasks.generateRelocatedReobfMappings.flatMap { it.outputMappings })

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
                includeMappings.flatMap { it.outputJar },
                reobfJar,
                ext.minecraftVersion
            )
             */
        }
    }
}
