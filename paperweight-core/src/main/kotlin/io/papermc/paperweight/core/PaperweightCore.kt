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
import io.papermc.paperweight.core.tasks.patchroulette.PatchRouletteTasks
import io.papermc.paperweight.core.util.coreExt
import io.papermc.paperweight.core.util.createBuildTasks
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

abstract class PaperweightCore : Plugin<Project> {
    @get:Inject
    abstract val layout: ProjectLayout

    @get:Inject
    abstract val dependencyFactory: DependencyFactory

    @get:Inject
    abstract val objects: ObjectFactory

    override fun apply(target: Project) {
        Git.checkForGit(target.providers)
        printId<PaperweightCore>("paperweight-core", target.gradle)

        val ext = target.extensions.create<PaperweightCoreExtension>(PAPERWEIGHT_EXTENSION, target)

        target.gradle.sharedServices.registerIfAbsent(DOWNLOAD_SERVICE_NAME, DownloadService::class) {
            parameters.projectPath.set(target.projectDir)
        }

        target.tasks.register<Delete>("cleanCache") {
            group = GENERAL_TASK_GROUP
            description = "Delete the project setup cache and task outputs."
            delete(layout.cache)
        }

        target.configurations.create(REMAPPER_CONFIG) {
            defaultDependencies {
                // Join list to avoid relocations breaking the string
                val coordinates = "${listOf("net", "fabricmc").joinToString(".")}:tiny-remapper:${LibraryVersions.TINY_REMAPPER}:fat"
                val remapper = dependencyFactory.create(coordinates).also { it.isTransitive = false }
                add(remapper)
            }
        }
        target.configurations.create(PAPERCLIP_CONFIG)
        val macheConfig = target.configurations.create(MACHE_CONFIG) {
            attributes.attribute(MacheOutput.ATTRIBUTE, objects.named(MacheOutput.ZIP))
        }
        target.configurations.register(MACHE_CODEBOOK_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_REMAPPER_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_DECOMPILER_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_PARAM_MAPPINGS_CONFIG) { isTransitive = false }
        target.configurations.register(MACHE_CONSTANTS_CONFIG) { isTransitive = false }
        val macheMinecraftLibrariesConfig = target.configurations.register(MACHE_MINECRAFT_LIBRARIES_CONFIG) {
            extendsFrom(macheConfig)
        }
        target.configurations.register(MACHE_MINECRAFT_CONFIG) {
            extendsFrom(macheMinecraftLibrariesConfig.get())
        }
        target.configurations.consumable(MAPPED_JAR_OUTGOING_CONFIG) // For source generator modules
        target.configurations.register(JST_CONFIG) {
            defaultDependencies {
                // add(project.dependencies.create("net.neoforged.jst:jst-cli-bundle:${LibraryVersions.JST}"))
                add(target.dependencies.create("io.papermc.jst:jst-cli-bundle:${LibraryVersions.JST}"))
            }
        }

        // impl extends minecraft
        target.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
            extendsFrom(macheMinecraftLibrariesConfig.get())
        }

        if (target.providers.gradleProperty("paperweight.dev").orNull == "true") {
            target.tasks.register<CreateDiffOutput>("diff") {
                inputDir.convention(ext.paper.paperServerDir.map { it.dir("src/main/java") })
                val prop = target.providers.gradleProperty("paperweight.diff.output")
                if (prop.isPresent) {
                    baseDir.convention(layout.projectDirectory.dir(prop))
                }
            }
        }

        val mache: Property<MacheMeta> = objects.property()
        val tasks = CoreTasks(target, mache)
        val devBundleTasks = DevBundleTasks(target, tasks)

        target.configurations.named(MAPPED_JAR_OUTGOING_CONFIG) {
            outgoing.artifact(tasks.macheRemapJar)
        }
        target.configurations.named(MACHE_MINECRAFT_CONFIG) {
            withDependencies {
                val minecraftJar = dependencyFactory.create(
                    layout.files(tasks.macheRemapJar.flatMap { it.outputJar })
                )
                add(minecraftJar)
            }
        }

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
            tasks.generateRelocatedReobfMappings.flatMap { it.outputMappings },
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
            // add Minecraft source dirs
            // for some reason doing this in #apply instead of afterEvaluate causes fork compileJava to take 5x longer (due to order of source dirs)
            target.extensions.configure<JavaPluginExtension> {
                sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
                    java {
                        srcDirs(this@PaperweightCore.layout.projectDirectory.dir("src/minecraft/java"))
                    }
                    resources {
                        srcDirs(this@PaperweightCore.layout.projectDirectory.dir("src/minecraft/resources"))
                    }
                }
            }

            repositories {
                maven(ext.macheRepo) {
                    name = MACHE_REPO_NAME
                    content { onlyForConfigurations(MACHE_CONFIG) }
                }
            }

            // load mache
            mache.set(configurations.resolveMacheMeta())
            mache.get().addRepositories(this)
            mache.get().addDependencies(this)

            tasks.afterEvaluate()

            devBundleTasks.configureAfterEvaluate(
                includeMappings.flatMap { it.outputJar },
            )

            if (coreExt.updatingMinecraft.oldPaperCommit.isPresent) {
                tasks.paperPatchingTasks.applySourcePatches.configure {
                    additionalRemote = layout.cache.resolve(
                        "$OLD_PAPER_PATH/${coreExt.updatingMinecraft.oldPaperCommit.get()}/paper-server/src/minecraft/java"
                    ).absolutePathString()
                }

                PatchRouletteTasks(
                    target,
                    "paper",
                    coreExt.minecraftVersion,
                    coreExt.paper.rejectsDir,
                    layout.projectDirectory.dir("src/minecraft/java"),
                )
            }
        }
    }
}
