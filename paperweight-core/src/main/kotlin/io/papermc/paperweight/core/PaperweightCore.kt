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
import io.papermc.paperweight.attribute.MacheOutput
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.core.taskcontainers.AllTasks
import io.papermc.paperweight.core.taskcontainers.BundlerJarTasks
import io.papermc.paperweight.core.taskcontainers.DevBundleTasks
import io.papermc.paperweight.core.taskcontainers.SoftSpoonTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

abstract class PaperweightCore : Plugin<Project> {
    override fun apply(target: Project) {
        Git.checkForGit(target.providers)
        printId<PaperweightCore>("paperweight-core", target.gradle)

        val ext = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightCoreExtension::class)

        target.gradle.sharedServices.registerIfAbsent(DOWNLOAD_SERVICE_NAME, DownloadService::class) {
            parameters.projectPath.set(target.projectDir)
        }

        target.tasks.register<Delete>("cleanCache") {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(REMAPPER_CONFIG) {
            defaultDependencies {
                add(
                    target.dependencies.create(
                        "${listOf("net", "fabricmc").joinToString(".")}:tiny-remapper:${LibraryVersions.TINY_REMAPPER}:fat"
                    ) {
                        isTransitive = false
                    }
                )
            }
        }
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
            ext.reobfPackagesToFix,
            tasks.generateRelocatedReobfMappings
        )
        val bundlerJarTasks = BundlerJarTasks(
            target,
            ext.bundlerJarName,
            ext.mainClass
        )
        bundlerJarTasks.configureBundlerTasks(
            tasks.extractFromBundler.flatMap { it.versionJson },
            tasks.extractFromBundler.flatMap { it.serverLibrariesList },
            tasks.downloadServerJar.flatMap { it.outputJar },
            includeMappings.flatMap { it.outputJar },
            reobfJar,
            ext.minecraftVersion
        )

        target.afterEvaluate {
            target.repositories {
                maven(ext.macheRepo) {
                    name = MACHE_REPO_NAME
                    content { onlyForConfigurations(MACHE_CONFIG) }
                }
            }

            softSpoonTasks.afterEvaluate()

            devBundleTasks.configure(
                ext.bundlerJarName.get(),
                ext.mainClass,
                ext.minecraftVersion,
                softSpoonTasks.setupMacheSourcesForDevBundle.flatMap { it.outputDir },
                tasks.extractFromBundler.map { it.serverLibrariesList.path },
                tasks.downloadServerJar.map { it.outputJar.path },
                tasks.extractFromBundler.map { it.versionJson.path }.convertToFileProvider(layout, providers)
            ) {
                reobfMappingsFile.set(tasks.generateRelocatedReobfMappings.flatMap { it.outputMappings })
            }
            devBundleTasks.configureAfterEvaluate()
        }
    }
}
