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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.coreExt
import io.papermc.paperweight.core.extension.ForkConfig
import io.papermc.paperweight.core.tasks.SetupMinecraftSources
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.DecompileJar
import io.papermc.paperweight.tasks.mache.RemapJar
import io.papermc.paperweight.tasks.softspoon.ImportLibraryFiles
import io.papermc.paperweight.tasks.softspoon.IndexLibraryFiles
import io.papermc.paperweight.tasks.softspoon.SetupPaperScript
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Files
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class CoreTasks(
    val project: Project,
    val mache: Property<MacheMeta>,
    tasks: TaskContainer = project.tasks
) : AllTasks(project) {
    val macheRemapJar by tasks.registering(RemapJar::class) {
        serverJar.set(extractFromBundler.flatMap { it.serverJar })
        serverMappings.set(downloadMappings.flatMap { it.outputFile })

        remapperArgs.set(mache.map { it.remapperArgs })
        codebookClasspath.from(project.configurations.named(MACHE_CODEBOOK_CONFIG))
        minecraftClasspath.from(project.configurations.named(MACHE_MINECRAFT_LIBRARIES_CONFIG))
        remapperClasspath.from(project.configurations.named(MACHE_REMAPPER_CONFIG))
        paramMappings.from(project.configurations.named(MACHE_PARAM_MAPPINGS_CONFIG))
        constants.from(project.configurations.named(MACHE_CONSTANTS_CONFIG))

        outputJar.set(layout.cache.resolve(FINAL_REMAPPED_CODEBOOK_JAR))
    }

    val macheDecompileJar by tasks.registering(DecompileJar::class) {
        inputJar.set(macheRemapJar.flatMap { it.outputJar })
        decompilerArgs.set(mache.map { it.decompilerArgs })

        minecraftClasspath.from(project.configurations.named(MACHE_MINECRAFT_LIBRARIES_CONFIG))
        decompiler.from(project.configurations.named(MACHE_DECOMPILER_CONFIG))

        outputJar.set(layout.cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val collectPaperATsFromPatches by tasks.registering(CollectATsFromPatches::class) {
        patchDir.set(project.coreExt.paper.featurePatchDir.fileExists(project))
    }

    val mergePaperATs by tasks.registering<MergeAccessTransforms> {
        firstFile.set(project.coreExt.paper.additionalAts.fileExists(project))
        secondFile.set(collectPaperATsFromPatches.flatMap { it.outputFile })
    }

    val indexLibraryFiles = tasks.register<IndexLibraryFiles>("indexLibraryFiles") {
        libraries.from(
            downloadRuntimeClasspathSources.flatMap { it.outputDir },
            downloadMcLibrariesSources.flatMap { it.outputDir }
        )
    }

    val importLibraryFiles = tasks.register<ImportLibraryFiles>("importPaperLibraryFiles") {
        patches.from(project.coreExt.paper.sourcePatchDir, project.coreExt.paper.featurePatchDir)
        devImports.set(project.coreExt.paper.devImports.fileExists(project))
        libraryFileIndex.set(indexLibraryFiles.flatMap { it.outputFile })
        libraries.from(indexLibraryFiles.map { it.libraries })
    }

    private fun SetupMinecraftSources.configureSetupMacheSources() {
        mache.from(project.configurations.named(MACHE_CONFIG))
        macheOld.set(project.coreExt.macheOldPath)
        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        predicate.set { Files.isRegularFile(it) && it.toString().endsWith(".java") }
    }

    val setupMacheSources by tasks.registering(SetupMinecraftSources::class) {
        description = "Setup Minecraft source dir (applying mache patches and paper ATs)."
        configureSetupMacheSources()
        libraryImports.set(importLibraryFiles.flatMap { it.outputDir })
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))

        atFile.set(mergePaperATs.flatMap { it.outputFile })
        ats.jstClasspath.from(project.configurations.named(MACHE_MINECRAFT_LIBRARIES_CONFIG))
        ats.jst.from(project.configurations.named(JST_CONFIG))
    }

    val setupMacheSourcesForDevBundle by tasks.registering(SetupMinecraftSources::class) {
        description = "Setup Minecraft source dir (applying mache patches)."
        configureSetupMacheSources()
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources_dev_bundle"))
    }

    val setupMacheResources by tasks.registering(SetupMinecraftSources::class) {
        description = "Setup Minecraft resources dir"

        inputFile.set(extractFromBundler.flatMap { it.serverJar })
        predicate.set { Files.isRegularFile(it) && !it.toString().endsWith(".class") }
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
    }

    fun afterEvaluate() {
        val mache = mache.get()

        setupPatchingTasks()

        project.configurations.named(MAPPED_JAR_OUTGOING_CONFIG) {
            outgoing.artifact(macheRemapJar)
        }

        // setup repos
        mache.addRepositories(project)

        // setup mc deps
        project.configurations.named(MACHE_MINECRAFT_LIBRARIES_CONFIG) {
            extendsFrom(project.configurations.getByName(MACHE_CONFIG))
        }
        project.configurations.named(MACHE_MINECRAFT_CONFIG) {
            extendsFrom(project.configurations.getByName(MACHE_MINECRAFT_LIBRARIES_CONFIG))
            withDependencies {
                add(
                    project.dependencies.create(
                        project.files(macheRemapJar.flatMap { it.outputJar })
                    )
                )
            }
        }

        // setup mache deps
        mache.addDependencies(project)

        // impl extends minecraft
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
            extendsFrom(project.configurations.getByName(MACHE_MINECRAFT_LIBRARIES_CONFIG))
        }

        // add Minecraft source dir
        project.the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
            java {
                srcDirs(project.projectDir.resolve("src/minecraft/java"))
            }
            resources {
                srcDirs(project.projectDir.resolve("src/minecraft/resources"))
            }
        }
    }

    private fun setupPatchingTasks() {
        val hasFork = project.coreExt.forks.isNotEmpty()

        if (!hasFork) {
            val setupPaperScript by project.tasks.registering(SetupPaperScript::class) {
                group = GENERAL_TASK_GROUP
                description = "Creates a util script and installs it into path"

                root.set(project.rootProject.layout.projectDirectory)
            }
        }

        // Setup Paper's Minecraft patching tasks
        val paperOutputRoot = if (hasFork) {
            project.upstreamsDirectory().get().path.resolve("server-work/paper")
        } else {
            project.layout.projectDirectory.path
        }
        val paperPatchingTasks = MinecraftPatchingTasks(
            project,
            "paper",
            true,
            this,
            hasFork,
            project.coreExt.paper.sourcePatchDir,
            project.coreExt.paper.rejectsDir,
            project.coreExt.paper.resourcePatchDir,
            project.coreExt.paper.featurePatchDir,
            project.coreExt.paper.additionalAts,
            setupMacheSources.flatMap { it.outputDir },
            setupMacheResources.flatMap { it.outputDir },
            project.coreExt.gitFilePatches,
            paperOutputRoot,
        )

        // Setup patching tasks for forks
        val patchingTasks: MutableMap<String, Pair<MinecraftPatchingTasks, UpstreamConfigTasks>> = mutableMapOf()
        fun makePatchingTasks(cfg: ForkConfig): Pair<MinecraftPatchingTasks, UpstreamConfigTasks> {
            val upstreamTasks = if (cfg.forksPaper.get()) {
                paperPatchingTasks to null
            } else {
                patchingTasks[cfg.forks.get().name]
                    ?: makePatchingTasks(cfg).also { patchingTasks[cfg.forks.get().name] = it }
            }

            val activeFork = project.coreExt.activeFork.get().name == cfg.name

            val outputRoot = if (activeFork) {
                project.layout.projectDirectory.path
            } else {
                project.upstreamsDirectory().path.resolve("server-work/${cfg.name}")
            }

            val serverTasks = MinecraftPatchingTasks(
                project,
                cfg.name,
                false,
                this,
                !activeFork,
                cfg.sourcePatchDir,
                cfg.rejectsDir,
                cfg.resourcePatchDir,
                cfg.featurePatchDir,
                cfg.additionalAts,
                upstreamTasks.first.applyFeaturePatches.flatMap { it.repo },
                upstreamTasks.first.applyResourcePatches.flatMap { it.output },
                project.coreExt.gitFilePatches,
                outputRoot,
            )

            serverTasks.setupFork(cfg)

            val upstreamConfigTasks = UpstreamConfigTasks(
                project,
                cfg.name,
                cfg.upstream,
                if (cfg.forksPaper.get()) {
                    project.coreExt.paper.paperServerDir.dir("../")
                } else {
                    cfg.forks.get().rootDirectory
                },
                !activeFork,
                if (activeFork) {
                    "server patching"
                } else {
                    "upstream server patching"
                },
                project.coreExt.gitFilePatches,
                null,
                upstreamTasks.second,
            )

            if (activeFork) {
                // setup output name conventions
                project.coreExt.bundlerJarName.convention(cfg.name)

                // setup aggregate -server patching tasks
                upstreamConfigTasks.setupAggregateTasks(
                    "Server",
                    cfg.upstream.directoryPatchSets.names.joinToString(", ")
                )

                // setup aggregate Minecraft & upstream -server patching tasks
                val applyAllServerFilePatches = project.tasks.register("applyAllServerFilePatches") {
                    group = "patching"
                    description = "Applies all Minecraft and upstream server file patches " +
                        "(equivalent to '${serverTasks.applyFilePatches.name} applyServerFilePatches')"
                    dependsOn(serverTasks.applyFilePatches)
                    dependsOn("applyServerFilePatches")
                }
                val applyAllServerFeaturePatches = project.tasks.register("applyAllServerFeaturePatches") {
                    group = "patching"
                    description = "Applies all Minecraft and upstream server feature patches " +
                        "(equivalent to '${serverTasks.applyFeaturePatches.name} applyServerFeaturePatches')"
                    dependsOn(serverTasks.applyFeaturePatches)
                    dependsOn("applyServerFeaturePatches")
                }
                val applyAllServerPatches = project.tasks.register("applyAllServerPatches") {
                    group = "patching"
                    description = "Applies all Minecraft and upstream server patches " +
                        "(equivalent to '${serverTasks.applyPatches.name} applyServerPatches')"
                    dependsOn(serverTasks.applyPatches)
                    dependsOn("applyServerPatches")
                }
                val rebuildAllServerFilePatches = project.tasks.register("rebuildAllServerFilePatches") {
                    group = "patching"
                    description = "Rebuilds all Minecraft and upstream server file patches " +
                        "(equivalent to '${serverTasks.rebuildFilePatchesName} rebuildServerFilePatches')"
                    dependsOn(serverTasks.rebuildFilePatchesName)
                    dependsOn("rebuildServerFilePatches")
                }
                val rebuildAllServerFeaturePatches = project.tasks.register("rebuildAllServerFeaturePatches") {
                    group = "patching"
                    description = "Rebuilds all Minecraft and upstream server feature patches " +
                        "(equivalent to '${serverTasks.rebuildFeaturePatchesName} rebuildServerFeaturePatches')"
                    dependsOn(serverTasks.rebuildFeaturePatchesName)
                    dependsOn("rebuildServerFeaturePatches")
                }
                val rebuildAllServerPatches = project.tasks.register("rebuildAllServerPatches") {
                    group = "patching"
                    description = "Rebuilds all Minecraft and upstream server patches " +
                        "(equivalent to '${serverTasks.rebuildPatchesName} rebuildServerPatches')"
                    dependsOn(serverTasks.rebuildPatchesName)
                    dependsOn("rebuildServerPatches")
                }
            }

            return serverTasks to upstreamConfigTasks
        }

        project.coreExt.forks.forEach { config ->
            if (config.name !in patchingTasks) {
                patchingTasks[config.name] = makePatchingTasks(config)
            }
        }
    }
}
