/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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
import io.papermc.paperweight.core.tasks.PaperweightCoreUpstreamData
import io.papermc.paperweight.tasks.GeneratePaperclipPatch
import io.papermc.paperweight.tasks.patchremap.RemapPatches
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.initSubmodules
import io.papermc.paperweight.util.registering
import io.papermc.paperweight.util.setupServerProject
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*

class PaperweightCore : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightCoreExtension::class)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        // Make sure the submodules are initialized, since there are files there
        // which are required for configuration
        target.layout.initSubmodules()

        target.configurations.create(Constants.PARAM_MAPPINGS_CONFIG)
        target.configurations.create(Constants.REMAPPER_CONFIG)
        target.configurations.create(Constants.DECOMPILER_CONFIG)
        target.configurations.create(Constants.PAPERCLIP_CONFIG)

        val tasks = AllTasks(target)
        target.createPatchRemapTask(tasks)

        target.tasks.register<PaperweightCoreUpstreamData>(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM) {
            dependsOn(tasks.applyPatches)
            vanillaJar.set(tasks.downloadServerJar.flatMap { it.outputJar })
            remappedJar.set(tasks.copyResources.flatMap { it.outputJar })
            decompiledJar.set(tasks.decompileJar.flatMap { it.outputJar })
            mcVersion.set(target.ext.minecraftVersion)
            mcLibrariesFile.set(tasks.inspectVanillaJar.flatMap { it.serverLibraries })
            mcLibrariesDir.set(tasks.downloadMcLibraries.flatMap { it.sourcesOutputDir })
            reobfMappings.set(tasks.generateReobfMappings.flatMap { it.reobfMappings })

            dataFile.set(target.layout.file(providers.gradleProperty(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM).map { File(it) }))
        }

        target.afterEvaluate {
            // Setup the server jar
            val cache = target.layout.cache

            val serverProj = target.ext.serverProject.forUseAtConfigurationTime().orNull ?: return@afterEvaluate
            val reobfJar = serverProj.setupServerProject(
                target,
                cache.resolve(Constants.FINAL_REMAPPED_JAR),
                cache.resolve(Constants.SERVER_LIBRARIES),
            ) {
                mappingsFile.set(tasks.generateReobfMappings.flatMap { it.reobfMappings })
            } ?: return@afterEvaluate

            val generatePaperclipPatch by target.tasks.registering<GeneratePaperclipPatch> {
                originalJar.set(tasks.downloadServerJar.flatMap { it.outputJar })
                patchedJar.set(reobfJar.flatMap { it.outputJar })
                mcVersion.set(target.ext.minecraftVersion)
            }

            @Suppress("UNUSED_VARIABLE")
            val paperclipJar by target.tasks.registering<Jar> {
                with(target.tasks.named("jar", Jar::class).get())

                val paperclipConfig = target.configurations.named(Constants.PAPERCLIP_CONFIG)
                dependsOn(paperclipConfig, generatePaperclipPatch)

                val paperclipZip = target.zipTree(paperclipConfig.map { it.singleFile })
                from(paperclipZip) {
                    exclude("META-INF/MANIFEST.MF")
                }
                from(target.zipTree(generatePaperclipPatch.flatMap { it.outputZip }))

                manifest.from(paperclipZip.matching { include("META-INF/MANIFEST.MF") }.singleFile)
            }
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
            group = "paper"
            description = "EXPERIMENTAL & BROKEN: Attempt to remap Paper's patches from Spigot mappings to SRG."

            inputPatchDir.set(extension.paper.unmappedSpigotServerPatchDir)
            apiPatchDir.set(extension.paper.spigotApiPatchDir)

            mappingsFile.set(allTasks.patchMappings.flatMap { it.outputMappings }.get())
            ats.set(allTasks.remapSpigotSources.flatMap { it.generatedAt }.get())

            // Pull in as many jars as possible to reduce the possibility of type bindings not resolving
            classpathJars.from(allTasks.applyMergedAt.flatMap { it.outputJar }.get()) // final remapped jar
            classpathJars.from(allTasks.remapSpigotSources.flatMap { it.vanillaRemappedSpigotJar }.get()) // Spigot remapped jar
            classpathJars.from(allTasks.downloadServerJar.flatMap { it.outputJar }.get()) // pure vanilla jar

            spigotApiDir.set(allTasks.patchSpigotApi.flatMap { it.outputDir }.get())
            spigotServerDir.set(allTasks.patchSpigotServer.flatMap { it.outputDir }.get())
            spigotDecompJar.set(allTasks.spigotDecompileJar.flatMap { it.outputJar }.get())

            // library class imports
            mcLibrarySourcesDir.set(allTasks.downloadMcLibraries.flatMap { it.sourcesOutputDir }.get())
            libraryImports.set(extension.paper.libraryImports)

            outputPatchDir.set(extension.paper.remappedSpigotServerPatchDir)
        }
    }
}
