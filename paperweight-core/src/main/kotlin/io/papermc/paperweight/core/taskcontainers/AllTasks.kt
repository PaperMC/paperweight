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

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class AllTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
    downloadService: Provider<DownloadService> = project.download
): VanillaTasks(project) {

    val applyAt by tasks.registering<ApplyAccessTransform> {
        inputJar.set(fixJar.flatMap { it.outputJar })
        atFile.set(mergePaperAts.flatMap { it.outputFile })
    }

    val copyResources by tasks.registering<CopyResources> {
        inputJar.set(applyAt.flatMap { it.outputJar })
        vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
        includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta", "flightrecorder-config.jfc"))
    }

    val decompileJar by tasks.registering<RunForgeFlower> {
        executable.from(project.configurations.named(DECOMPILER_CONFIG))

        inputJar.set(copyResources.flatMap { it.outputJar })
        libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })

        outputJar.set(cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val lineMapJar by tasks.registering<LineMapJar> {
        inputJar.set(copyResources.flatMap { it.outputJar })
        outputJar.set(cache.resolve(FINAL_REMAPPED_JAR))
        decompiledJar.set(decompileJar.flatMap { it.outputJar })
    }

    val downloadMcLibrariesSources by tasks.registering<DownloadMcLibraries> {
        mcLibrariesFile.set(extractFromBundler.flatMap { it.serverLibrariesTxt })
        repositories.set(listOf(MC_LIBRARY_URL, MAVEN_CENTRAL_URL))
        outputDir.set(cache.resolve(MINECRAFT_SOURCES_PATH))
        sources.set(true)

        downloader.set(downloadService)
    }

    val downloadPatchSets by tasks.registering<DownloadPatchesTask> {
        patchSets.set(extension.paper.patchSets)
        outputDir.set(cache.resolve(PATCHES_PATH))
        downloader.set(project.download)
    }

    val generateSrgCsv by tasks.registering<GenerateSrgCsv> {
        // TODO temp, to speed up stuff
        //ourMappings.set(generateMappings.flatMap { it.outputMappings })
        ourMappings.set(cache.resolve(MOJANG_YARN_MAPPINGS))
        srgMappings.set(cache.resolve(TEMP_SRG)) // TODO properly extract me

        outputCsv.set(cache.resolve(SRG_CSV))
    }

    @Suppress("unused")
    val applyPatchSets by tasks.registering<ApplyPatchSets> {
        group = "paper"
        description = "Setup the Paper projects"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }
        printOutput.set(project.isBaseExecution)

        patchSets.set(extension.paper.patchSets)
        //patchesDir.set(downloadPatchSets.flatMap { it.outputDir })
        outputDir.set(extension.paper.paperServerDir)
        workDir.set(project.file("work")) // TODO

        // TODO temp, to speed up stuff
        sourceMcDevJar.set(decompileJar.flatMap { it.outputJar })
        //sourceMcDevJar.set(cache.resolve(FINAL_DECOMPILE_JAR))
        //mcLibrariesDir.set(downloadMcLibrariesSources.flatMap { it.outputDir })
        //mcLibrariesDir.set(cache.resolve(MINECRAFT_SOURCES_PATH))
        //devImports.set(extension.paper.devImports.fileExists(project))
        //srgCsv.set(generateSrgCsv.flatMap { it.outputCsv })
    }

    val rebuildApiPatches by tasks.registering<RebuildGitPatches> {
        group = "paper"
        description = "Rebuilds patches to api"
        inputDir.set(extension.paper.paperApiDir)
        baseRef.set("base")

        //patchDir.set(extension.paper.spigotApiPatchDir)
    }

    val rebuildServerPatches by tasks.registering<RebuildGitPatches> {
        group = "paper"
        description = "Rebuilds patches to server"
        inputDir.set(extension.paper.paperServerDir)
        baseRef.set("base")

        //patchDir.set(extension.paper.spigotServerPatchDir)
    }

    @Suppress("unused")
    val rebuildPatches by tasks.registering<Task> {
        group = "paper"
        description = "Rebuilds patches to api and server"
        dependsOn(rebuildApiPatches, rebuildServerPatches)
    }
}
