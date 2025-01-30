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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.extension.ForkConfig
import io.papermc.paperweight.core.tasks.ImportLibraryFiles
import io.papermc.paperweight.core.tasks.IndexLibraryFiles
import io.papermc.paperweight.core.tasks.SetupMinecraftSources
import io.papermc.paperweight.core.tasks.SetupPaperScript
import io.papermc.paperweight.core.util.coreExt
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.DecompileJar
import io.papermc.paperweight.tasks.mache.RunCodebook
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Files
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

class CoreTasks(
    val project: Project,
    val mache: Property<MacheMeta>,
    tasks: TaskContainer = project.tasks
) : AllTasks(project) {
    lateinit var paperPatchingTasks: MinecraftPatchingTasks

    val macheRemapJar by tasks.registering(RunCodebook::class) {
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
        oldPaperCommit.convention(project.coreExt.updatingMinecraft.oldPaperCommit)
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
        setupPatchingTasks()
    }

    private fun setupPatchingTasks() {
        val hasFork = project.coreExt.forks.isNotEmpty()

        if (hasFork) {
            project.coreExt.paper.rootDirectory.set(
                project.upstreamsDirectory().map { it.dir("paper") }
            )
            project.coreExt.forks.forEach { fork ->
                val activeFork = project.coreExt.activeFork.get().name == fork.name
                if (!activeFork) {
                    fork.rootDirectory.set(
                        project.upstreamsDirectory().map { it.dir(fork.name) }
                    )
                }
            }
        }

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
        paperPatchingTasks = MinecraftPatchingTasks(
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
            project.coreExt.filterPatches,
            paperOutputRoot,
        )

        // Setup patching tasks for forks
        val patchingTasks: MutableMap<String, Pair<MinecraftPatchingTasks, UpstreamConfigTasks>> = mutableMapOf()
        fun makePatchingTasks(cfg: ForkConfig): Pair<MinecraftPatchingTasks, UpstreamConfigTasks> {
            val upstreamTasks = if (cfg.forksPaper.get()) {
                paperPatchingTasks to null
            } else {
                val tasks = patchingTasks[cfg.forks.get().name]
                requireNotNull(tasks) { "Upstream patching tasks for ${cfg.forks.get().name} not present when expected" }
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
                project.coreExt.filterPatches,
                outputRoot,
            )

            serverTasks.setupFork(cfg)

            val upstreamConfigTasks = UpstreamConfigTasks(
                project,
                cfg.name,
                cfg.upstream,
                if (cfg.forksPaper.get()) {
                    project.coreExt.paper.rootDirectory
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
                project.coreExt.filterPatches,
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

        if (project.coreExt.forks.isNotEmpty()) {
            forkPatchingTaskOrder().forEach { config ->
                patchingTasks[config.name] = makePatchingTasks(config)
            }
        }
    }

    private fun forkPatchingTaskOrder(): List<ForkConfig> {
        val order = mutableListOf<ForkConfig>()
        val forks = project.coreExt.forks.toMutableList()
        val forksPaper = forks.filter { it.forksPaper.get() }
        if (forksPaper.size != 1) {
            throw PaperweightException("Multiple ForkConfigs are set to fork Paper, but only one is allowed. ${forksPaper.map { it.name }}")
        }
        order.addAll(forksPaper)
        forks.removeAll(forksPaper)

        var current: ForkConfig? = order.last()
        while (current != null) {
            val deps = forks.filter { it.forks.get().name == requireNotNull(current).name }
            if (deps.isNotEmpty()) {
                if (deps.size != 1) {
                    throw PaperweightException(
                        "Multiple ForkConfigs are set to fork ${current.name}, but only one is allowed. ${deps.map { it.name }}"
                    )
                }
                order.addAll(deps)
                forks.removeAll(deps)
                current = order.last()
            } else {
                if (forks.isNotEmpty()) {
                    throw PaperweightException(
                        "ForkConfigs are not in a valid order. Current order: ${order.map { it.name }}; Remaining: ${forks.map { it.name }}"
                    )
                }
                current = null
            }
        }
        project.logger.info("Fork order: {}", order.joinToString(" -> ") { it.name })
        return order
    }
}
