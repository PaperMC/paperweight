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
import io.papermc.paperweight.core.taskcontainers.CoreTasks
import io.papermc.paperweight.core.taskcontainers.DevBundleTasks
import io.papermc.paperweight.core.taskcontainers.PaperclipTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
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
            group = GENERAL_TASK_GROUP
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
        target.configurations.register(MACHE_CODEBOOK_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_REMAPPER_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_DECOMPILER_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_PARAM_MAPPINGS_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_CONSTANTS_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_MINECRAFT_LIBRARIES_CONFIG)
        target.configurations.consumable(MAPPED_JAR_OUTGOING_CONFIG) // For source generator modules
        target.configurations.register(MACHE_MINECRAFT_CONFIG)
        target.configurations.register(JST_CONFIG) {
            defaultDependencies {
                // add(project.dependencies.create("net.neoforged.jst:jst-cli-bundle:${JSTVersion.VERSION}"))
                add(target.dependencies.create("io.papermc.jst:jst-cli-bundle:${LibraryVersions.JST}"))
            }
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

        val mache: Property<MacheMeta> = target.objects.property()
        val tasks = CoreTasks(target, mache)
        val devBundleTasks = DevBundleTasks(target, tasks)

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
        PaperclipTasks(
            target,
            ext.bundlerJarName,
            ext.mainClass,
            tasks.extractFromBundler.flatMap { it.versionJson },
            tasks.extractFromBundler.flatMap { it.serverLibrariesList },
            tasks.downloadServerJar.flatMap { it.outputJar },
            includeMappings.flatMap { it.outputJar },
            reobfJar.flatMap { it.outputJar },
            ext.minecraftVersion,
        )

        target.afterEvaluate {
            target.repositories {
                maven(ext.macheRepo) {
                    name = MACHE_REPO_NAME
                    content { onlyForConfigurations(MACHE_CONFIG) }
                }
            }

            // load mache
            mache.set(project.configurations.resolveMacheMeta())

            tasks.afterEvaluate()

            devBundleTasks.configureAfterEvaluate(
                includeMappings.flatMap { it.outputJar },
            )
        }
    }
}
