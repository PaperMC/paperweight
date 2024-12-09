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

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.restamp.RestampVersion
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.DecompileJar
import io.papermc.paperweight.tasks.mache.RemapJar
import io.papermc.paperweight.tasks.mache.SetupVanilla
import io.papermc.paperweight.tasks.softspoon.ApplyFeaturePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatchesFuzzy
import io.papermc.paperweight.tasks.softspoon.FixupFilePatches
import io.papermc.paperweight.tasks.softspoon.RebuildFilePatches
import io.papermc.paperweight.tasks.softspoon.SetupPaperScript
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Files
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

open class SoftSpoonTasks(
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
    val restampConfig = project.configurations.register(RESTAMP_CONFIG) {
        defaultDependencies {
            add(project.dependencies.create("io.papermc.restamp:restamp:${RestampVersion.VERSION}"))
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

    val collectAccessTransform by tasks.registering(CollectATsFromPatches::class) {
        group = "mache"

        patchDir.set(project.ext.paper.featurePatchDir)
    }

    val mergeCollectedAts by tasks.registering<MergeAccessTransforms> {
        firstFile.set(project.ext.paper.additionalAts.fileExists(project))
        secondFile.set(collectAccessTransform.flatMap { it.outputFile })
    }

    private fun SetupVanilla.configureSetupMacheSources() {
        group = "mache"

        mache.from(project.configurations.named(MACHE_CONFIG))
        macheOld.set(project.ext.macheOldPath)
        machePatches.set(layout.cache.resolve(PATCHES_FOLDER))
        minecraftClasspath.from(macheMinecraftLibraries)

        paperPatches.from(project.ext.paper.sourcePatchDir, project.ext.paper.featurePatchDir)
        devImports.set(project.ext.paper.devImports.fileExists(project))

        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        predicate.set { Files.isRegularFile(it) && it.toString().endsWith(".java") }
    }

    val setupMacheSources by tasks.registering(SetupVanilla::class) {
        description = "Setup vanilla source dir (applying mache patches and paper ATs)."
        configureSetupMacheSources()
        libraries.from(
            allTasks.downloadPaperLibrariesSources.flatMap { it.outputDir },
            allTasks.downloadMcLibrariesSources.flatMap { it.outputDir }
        )
        ats.set(mergeCollectedAts.flatMap { it.outputFile })
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
        restamp.from(restampConfig)
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

    val applySourcePatches by tasks.registering(ApplyFilePatches::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla sources"

        input.set(setupMacheSources.flatMap { it.outputDir })
        output.set(layout.projectDirectory.dir("src/vanilla/java"))
        patches.set(project.ext.paper.sourcePatchDir)
        rejects.set(project.ext.paper.rejectsDir)
        gitFilePatches.set(project.ext.gitFilePatches)
    }

    val applySourcePatchesFuzzy by tasks.registering(ApplyFilePatchesFuzzy::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla sources"

        input.set(setupMacheSources.flatMap { it.outputDir })
        output.set(layout.projectDirectory.dir("src/vanilla/java"))
        patches.set(project.ext.paper.sourcePatchDir)
        rejects.set(project.ext.paper.rejectsDir)
        gitFilePatches.set(project.ext.gitFilePatches)
    }

    val applyResourcePatches by tasks.registering(ApplyFilePatches::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla resources"

        input.set(setupMacheResources.flatMap { it.outputDir })
        output.set(layout.projectDirectory.dir("src/vanilla/resources"))
        patches.set(project.ext.paper.resourcePatchDir)
    }

    val applyFilePatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Applies all file patches"
        dependsOn(applySourcePatches, applyResourcePatches)
    }

    val applyFeaturePatches by tasks.registering(ApplyFeaturePatches::class) {
        group = "softspoon"
        description = "Applies all feature patches"
        dependsOn(applyFilePatches)

        repo.set(layout.projectDirectory.dir("src/vanilla/java"))
        patches.set(project.ext.paper.featurePatchDir)
    }

    val applyPatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Applies all patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildSourcePatches by tasks.registering(RebuildFilePatches::class) {
        group = "softspoon"
        description = "Rebuilds patches to the vanilla sources"

        minecraftClasspath.from(macheMinecraft)
        atFile.set(project.ext.paper.additionalAts.fileExists(project))
        atFileOut.set(project.ext.paper.additionalAts.fileExists(project))

        base.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
        input.set(layout.projectDirectory.dir("src/vanilla/java"))
        patches.set(project.ext.paper.sourcePatchDir)
        gitFilePatches.set(project.ext.gitFilePatches)

        restamp.from(restampConfig)
    }

    val rebuildResourcePatches by tasks.registering(RebuildFilePatches::class) {
        group = "softspoon"
        description = "Rebuilds patches to the vanilla resources"

        base.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
        input.set(layout.projectDirectory.dir("src/vanilla/resources"))
        patches.set(project.ext.paper.resourcePatchDir)
    }

    val rebuildFilePatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Rebuilds all file patches"
        dependsOn(rebuildSourcePatches, rebuildResourcePatches)
    }

    val rebuildFeaturePatches by tasks.registering(RebuildGitPatches::class) {
        group = "softspoon"
        description = "Rebuilds all feature patches"
        dependsOn(rebuildFilePatches)

        inputDir.set(layout.projectDirectory.dir("src/vanilla/java"))
        patchDir.set(project.ext.paper.featurePatchDir)
        baseRef.set("file")
    }

    val rebuildPatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Rebuilds all file patches"
        dependsOn(rebuildFilePatches, rebuildFeaturePatches)
    }

    val fixupSourcePatches by tasks.registering(FixupFilePatches::class) {
        group = "softspoon"
        description = "Puts the currently tracked source changes into the file patches commit"

        repo.set(layout.projectDirectory.dir("src/vanilla/java"))
    }

    val fixupResourcePatches by tasks.registering(FixupFilePatches::class) {
        group = "softspoon"
        description = "Puts the currently tracked resource changes into the file patches commit"

        repo.set(layout.projectDirectory.dir("src/vanilla/resources"))
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
        println("Loaded mache ${mache.macheVersion} for minecraft ${mache.minecraftVersion}")

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
}
