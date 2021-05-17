/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.plugin

import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.ApplyPaperPatches
import io.papermc.paperweight.tasks.CopyResources
import io.papermc.paperweight.tasks.RebuildPaperPatches
import io.papermc.paperweight.tasks.RunForgeFlower
import io.papermc.paperweight.tasks.patchremap.ApplyAccessTransform
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.registering
import io.papermc.paperweight.util.set
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class AllTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightExtension = project.ext,
) : SpigotTasks(project) {

    val applyMergedAt by tasks.registering<ApplyAccessTransform> {
        inputJar.set(fixJar.flatMap { it.outputJar })
        atFile.set(mergeGeneratedAts.flatMap { it.outputFile })
    }

    val copyResources by tasks.registering<CopyResources> {
        inputJar.set(applyMergedAt.flatMap { it.outputJar })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
        includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta"))

        outputJar.set(cache.resolve(Constants.FINAL_REMAPPED_JAR))
    }

    val decompileJar by tasks.registering<RunForgeFlower> {
        executable.fileProvider(project.configurations.named(Constants.DECOMPILER_CONFIG).map { it.singleFile })

        inputJar.set(copyResources.flatMap { it.outputJar })
        libraries.set(downloadMcLibraries.flatMap { it.outputDir })
    }

    val patchPaperApi by tasks.registering<ApplyGitPatches> {
        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(patchSpigotApi.flatMap { it.outputDir })
        patchDir.set(extension.paper.spigotApiPatchDir)
        printOutput.set(true)

        outputDir.set(extension.paper.paperApiDir)
    }

    @Suppress("DuplicatedCode")
    val patchPaperServer by tasks.registering<ApplyPaperPatches> {
        patchDir.set(extension.paper.spigotServerPatchDir)
        remappedSource.set(remapSpigotSources.flatMap { it.sourcesOutputZip })
        remappedTests.set(remapSpigotSources.flatMap { it.testsOutputZip })
        spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
        sourceMcDevJar.set(decompileJar.flatMap { it.outputJar })
        mcLibrariesDir.set(downloadMcLibraries.flatMap { it.sourcesOutputDir })
        libraryImports.set(extension.paper.libraryClassImports)

        outputDir.set(extension.paper.paperServerDir)
    }

    val patchPaper by tasks.registering<Task> {
        group = "Paper"
        description = "Set up the Paper development environment"
        dependsOn(patchPaperApi, patchPaperServer)
    }

    val rebuildPaperApi by tasks.registering<RebuildPaperPatches> {
        inputDir.set(extension.paper.paperApiDir)

        patchDir.set(extension.paper.spigotApiPatchDir)
    }

    val rebuildPaperServer by tasks.registering<RebuildPaperPatches> {
        inputDir.set(extension.paper.paperServerDir)
        server.set(true)

        patchDir.set(extension.paper.spigotServerPatchDir)
    }

    val rebuildPaperPatches by tasks.registering<Task> {
        group = "Paper"
        description = "Rebuilds patches to api and server"
        dependsOn(rebuildPaperApi, rebuildPaperServer)
    }
}
