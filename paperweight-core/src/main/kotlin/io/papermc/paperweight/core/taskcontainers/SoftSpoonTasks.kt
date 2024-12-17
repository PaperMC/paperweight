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
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.DecompileJar
import io.papermc.paperweight.tasks.mache.RemapJar
import io.papermc.paperweight.tasks.mache.SetupVanilla
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

class SoftSpoonTasks(
    val project: Project,
    val allTasks: AllTasks,
    tasks: TaskContainer = project.tasks
) {

    private val mache: Property<MacheMeta> = project.objects.property()

    val macheCodebook = project.configurations.register(MACHE_CODEBOOK_CONFIG) {
        isTransitive = false
    }
    val macheRemapper = project.configurations.register(MACHE_REMAPPER_CONFIG) {
        isTransitive = false
    }
    val macheDecompiler = project.configurations.register(MACHE_DECOMPILER_CONFIG) {
        isTransitive = false
    }
    val macheParamMappings = project.configurations.register(MACHE_PARAM_MAPPINGS_CONFIG) {
        isTransitive = false
    }
    val macheConstants = project.configurations.register(MACHE_CONSTANTS_CONFIG) {
        isTransitive = false
    }
    val macheMinecraftLibraries by project.configurations.registering
    val mappedJarOutgoing = project.configurations.consumable("mappedJarOutgoing") // For source generator modules
    val macheMinecraft by project.configurations.registering
    val jstConfig = project.configurations.register(JST_CONFIG) {
        defaultDependencies {
            // add(project.dependencies.create("net.neoforged.jst:jst-cli-bundle:${JSTVersion.VERSION}"))
            add(project.dependencies.create("io.papermc.jst:jst-cli-bundle:${LibraryVersions.JST}"))
        }
    }

    val macheRemapJar by tasks.registering(RemapJar::class) {
        group = "mache"
        serverJar.set(allTasks.extractFromBundler.flatMap { it.serverJar })
        serverMappings.set(allTasks.downloadMappings.flatMap { it.outputFile })

        remapperArgs.set(mache.map { it.remapperArgs })
        codebookClasspath.from(macheCodebook)
        minecraftClasspath.from(macheMinecraftLibraries)
        remapperClasspath.from(macheRemapper)
        paramMappings.from(macheParamMappings)
        constants.from(macheConstants)

        outputJar.set(layout.cache.resolve(FINAL_REMAPPED_CODEBOOK_JAR))
    }

    val macheDecompileJar by tasks.registering(DecompileJar::class) {
        group = "mache"
        inputJar.set(macheRemapJar.flatMap { it.outputJar })
        decompilerArgs.set(mache.map { it.decompilerArgs })

        minecraftClasspath.from(macheMinecraftLibraries)
        decompiler.from(macheDecompiler)

        outputJar.set(layout.cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val collectPaperATsFromPatches by tasks.registering(CollectATsFromPatches::class) {
        group = "mache"

        patchDir.set(project.coreExt.paper.featurePatchDir.fileExists(project))
    }

    val mergePaperATs by tasks.registering<MergeAccessTransforms> {
        firstFile.set(project.coreExt.paper.additionalAts.fileExists(project))
        secondFile.set(collectPaperATsFromPatches.flatMap { it.outputFile })
    }

    val indexLibraryFiles = tasks.register<IndexLibraryFiles>("indexLibraryFiles") {
        libraries.from(
            allTasks.downloadPaperLibrariesSources.flatMap { it.outputDir },
            allTasks.downloadMcLibrariesSources.flatMap { it.outputDir }
        )
    }

    val importLibraryFiles = tasks.register<ImportLibraryFiles>("importPaperLibraryFiles") {
        patches.from(project.coreExt.paper.sourcePatchDir, project.coreExt.paper.featurePatchDir)
        devImports.set(project.coreExt.paper.devImports.fileExists(project))
        libraryFileIndex.set(indexLibraryFiles.flatMap { it.outputFile })
        libraries.from(indexLibraryFiles.map { it.libraries })
    }

    private fun SetupVanilla.configureSetupMacheSources() {
        group = "mache"

        mache.from(project.configurations.named(MACHE_CONFIG))
        macheOld.set(project.coreExt.macheOldPath)
        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        predicate.set { Files.isRegularFile(it) && it.toString().endsWith(".java") }
    }

    val setupMacheSources by tasks.registering(SetupVanilla::class) {
        description = "Setup vanilla source dir (applying mache patches and paper ATs)."
        configureSetupMacheSources()
        libraryImports.set(importLibraryFiles.flatMap { it.outputDir })
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))

        atFile.set(mergePaperATs.flatMap { it.outputFile })
        ats.jstClasspath.from(macheMinecraftLibraries)
        ats.jst.from(jstConfig)
    }

    val setupMacheSourcesForDevBundle by tasks.registering(SetupVanilla::class) {
        description = "Setup vanilla source dir (applying mache patches)."
        configureSetupMacheSources()
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources_dev_bundle"))
    }

    val setupMacheResources by tasks.registering(SetupVanilla::class) {
        group = "mache"
        description = "Setup vanilla resources dir"

        inputFile.set(allTasks.extractFromBundler.flatMap { it.serverJar })
        predicate.set { Files.isRegularFile(it) && !it.toString().endsWith(".class") }
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
    }

    val setupPaperScript by tasks.registering(SetupPaperScript::class) {
        group = "softspoon"
        description = "Creates a util script and installs it into path"

        root.set(project.projectDir)
    }

    fun afterEvaluate() {
        // load mache
        mache.set(project.configurations.resolveMacheMeta())
        val mache = mache.get()

        setupPatchingTasks()

        mappedJarOutgoing {
            outgoing.artifact(macheRemapJar)
        }

        // setup repos
        mache.addRepositories(project)

        // setup mc deps
        macheMinecraftLibraries {
            extendsFrom(project.configurations.getByName(MACHE_CONFIG))
        }
        macheMinecraft {
            extendsFrom(macheMinecraftLibraries.get())
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
            extendsFrom(macheMinecraftLibraries.get())
        }

        // add vanilla source set
        project.the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
            java {
                srcDirs(project.projectDir.resolve("src/vanilla/java"))
            }
            resources {
                srcDirs(project.projectDir.resolve("src/vanilla/resources"))
            }
        }
    }

    private fun setupPatchingTasks() {
        val hasFork = project.coreExt.forks.isNotEmpty()

        // Setup Paper's vanilla patching tasks
        val paperOutputRoot = if (hasFork) {
            project.upstreamsDirectory().get().path.resolve("server-work/paper")
        } else {
            project.layout.projectDirectory.path
        }
        val paperPatchingTasks = ServerPatchingTasks(
            project,
            "paper",
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

        // For forks, setup aggregate vanilla & upstream -server patching tasks
        val applyAllServerFilePatches = if (!hasFork) {
            null
        } else {
            project.tasks.register("applyAllServerFilePatches") {
                group = "patching"
            }
        }
        val applyAllServerFeaturePatches = if (!hasFork) {
            null
        } else {
            project.tasks.register("applyAllServerFeaturePatches") {
                group = "patching"
            }
        }
        val applyAllServerPatches = if (!hasFork) {
            null
        } else {
            project.tasks.register("applyAllServerPatches") {
                group = "patching"
            }
        }
        val rebuildAllServerFilePatches = if (!hasFork) {
            null
        } else {
            project.tasks.register("rebuildAllServerFilePatches") {
                group = "patching"
            }
        }
        val rebuildAllServerFeaturePatches = if (!hasFork) {
            null
        } else {
            project.tasks.register("rebuildAllServerFeaturePatches") {
                group = "patching"
            }
        }
        val rebuildAllServerPatches = if (!hasFork) {
            null
        } else {
            project.tasks.register("rebuildAllServerPatches") {
                group = "patching"
            }
        }

        // Setup patching tasks for forks
        val patchingTasks: MutableMap<String, Pair<ServerPatchingTasks, UpstreamConfigTasks>> = mutableMapOf()
        fun makePatchingTasks(cfg: ForkConfig): Pair<ServerPatchingTasks, UpstreamConfigTasks> {
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

            val serverTasks = ServerPatchingTasks(
                project,
                cfg.name,
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

            serverTasks.setupAts(cfg)

            val upstreamConfigTasks = UpstreamConfigTasks(
                project,
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
                upstreamConfigTasks.setupAggregateTasks("Server")
                applyAllServerFilePatches?.configure { dependsOn("applyServerFilePatches") }
                applyAllServerFeaturePatches?.configure { dependsOn("applyServerFeaturePatches") }
                applyAllServerPatches?.configure { dependsOn("applyServerPatches") }
                rebuildAllServerFilePatches?.configure { dependsOn("rebuildServerFilePatches") }
                rebuildAllServerFeaturePatches?.configure { dependsOn("rebuildServerFeaturePatches") }
                rebuildAllServerPatches?.configure { dependsOn("rebuildServerPatches") }
            }

            return serverTasks to upstreamConfigTasks
        }

        project.coreExt.forks.forEach { config ->
            if (config.name !in patchingTasks) {
                patchingTasks[config.name] = makePatchingTasks(config)
            }
        }

        applyAllServerFilePatches?.configure { dependsOn("applyVanillaFilePatches") }
        applyAllServerFeaturePatches?.configure { dependsOn("applyVanillaFeaturePatches") }
        applyAllServerPatches?.configure { dependsOn("applyVanillaPatches") }
        rebuildAllServerFilePatches?.configure { dependsOn("rebuildVanillaFilePatches") }
        rebuildAllServerFeaturePatches?.configure { dependsOn("rebuildVanillaFeaturePatches") }
        rebuildAllServerPatches?.configure { dependsOn("rebuildVanillaPatches") }
    }
}
