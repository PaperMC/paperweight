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
import io.papermc.paperweight.tasks.mm.*
import io.papermc.paperweight.tasks.mm.filterrepo.*
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
) : SpigotTasks(project) {

    val mergeAdditionalAts by tasks.registering<MergeAccessTransforms> {
        firstFile.set(mergeGeneratedAts.flatMap { it.outputFile })
        secondFile.set(mergePaperAts.flatMap { it.outputFile })
    }

    val applyMergedAt by tasks.registering<ApplyAccessTransform> {
        inputJar.set(fixJar.flatMap { it.outputJar })
        atFile.set(mergeAdditionalAts.flatMap { it.outputFile })
    }

    val copyResources by tasks.registering<CopyResources> {
        inputJar.set(applyMergedAt.flatMap { it.outputJar })
        vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
    }

    val decompileJar by tasks.registering<RunVineFlower> {
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

    val applyApiPatches by tasks.registering<ApplyGitPatches> {
        group = "paper"
        description = "Setup the Paper-API project"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }
        printOutput.set(project.isBaseExecution)

        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(patchSpigotApi.flatMap { it.outputDir })
        patchDir.set(extension.paper.spigotApiPatchDir)
        unneededFiles.value(listOf("README.md"))

        outputDir.set(extension.paper.paperApiDir)
    }

    val downloadMcLibrariesSources by tasks.registering<DownloadMcLibraries> {
        mcLibrariesFile.set(extractFromBundler.flatMap { it.serverLibrariesTxt })
        repositories.set(listOf(MC_LIBRARY_URL, MAVEN_CENTRAL_URL))
        outputDir.set(cache.resolve(MINECRAFT_SOURCES_PATH))
        sources.set(true)

        downloader.set(downloadService)
    }

    @Suppress("DuplicatedCode")
    val applyServerPatches by tasks.registering<ApplyPaperPatches> {
        group = "paper"
        description = "Setup the Paper-Server project"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }
        printOutput.set(project.isBaseExecution)

        patchDir.set(extension.paper.spigotServerPatchDir)
        remappedSource.set(remapSpigotSources.flatMap { it.sourcesOutputZip })
        remappedTests.set(remapSpigotSources.flatMap { it.testsOutputZip })
        caseOnlyClassNameChanges.set(cleanupSourceMappings.flatMap { it.caseOnlyNameChanges })
        upstreamDir.set(patchSpigotServer.flatMap { it.outputDir })
        sourceMcDevJar.set(decompileJar.flatMap { it.outputJar })
        mcLibrariesDir.set(downloadMcLibrariesSources.flatMap { it.outputDir })
        spigotLibrariesDir.set(downloadSpigotDependencies.flatMap { it.outputSourcesDir })
        devImports.set(extension.paper.devImports.fileExists(project))
        unneededFiles.value(listOf("nms-patches", "applyPatches.sh", "CONTRIBUTING.md", "makePatches.sh", "README.md"))

        outputDir.set(extension.paper.paperServerDir)
        mcDevSources.set(extension.mcDevSourceDir)
    }

    val applyPatches by tasks.registering<Task> {
        group = "paper"
        description = "Set up the Paper development environment"
        dependsOn(applyApiPatches, applyServerPatches)
    }

    val rebuildApiPatches by tasks.registering<RebuildGitPatches> {
        group = "paper"
        description = "Rebuilds patches to api"
        inputDir.set(extension.paper.paperApiDir)
        baseRef.set("base")

        patchDir.set(extension.paper.spigotApiPatchDir)
    }

    val rebuildServerPatches by tasks.registering<RebuildGitPatches> {
        group = "paper"
        description = "Rebuilds patches to server"
        inputDir.set(extension.paper.paperServerDir)
        baseRef.set("base")

        patchDir.set(extension.paper.spigotServerPatchDir)
    }

    @Suppress("unused")
    val rebuildPatches by tasks.registering<Task> {
        group = "paper"
        description = "Rebuilds patches to api and server"
        dependsOn(rebuildApiPatches, rebuildServerPatches)
    }

    val generateReobfMappings by tasks.registering<GenerateReobfMappings> {
        inputMappings.set(patchMappings.flatMap { it.outputMappings })
        notchToSpigotMappings.set(generateSpigotMappings.flatMap { it.notchToSpigotMappings })
        sourceMappings.set(generateMappings.flatMap { it.outputMappings })
        spigotRecompiledClasses.set(remapSpigotSources.flatMap { it.spigotRecompiledClasses })

        reobfMappings.set(cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val patchReobfMappings by tasks.registering<PatchMappings> {
        inputMappings.set(generateReobfMappings.flatMap { it.reobfMappings })
        patch.set(extension.paper.reobfMappingsPatch.fileExists(project))

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        outputMappings.set(cache.resolve(PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val generateRelocatedReobfMappings by tasks.registering<GenerateRelocatedReobfMappings> {
        inputMappings.set(patchReobfMappings.flatMap { it.outputMappings })
        outputMappings.set(cache.resolve(RELOCATED_PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    /*
     API Tasks
     cloneRepos -> create fresh clones from the work/ dirs
     applySpigotApiPatches -> apply patches from the spigot repo on top of the bukkit API
     rewriteBukkitSpigotHistory -> rewrites all api commits to have a set author
     applyPaperApiPatches -> applies all paper API patches
     finalizeApiHistory -> move all api commits to a subdirectory

     Server Tasks

     */
    val cloneBranchName = "for-clone"
    val cloneRepos by tasks.registering<CloneRepos> {
        dependsOn(initSubmodules)
        cloneBranch.set(cloneBranchName)
        craftBukkitDir.set(extension.craftBukkit.craftBukkitDir)
        bukkitDir.set(extension.craftBukkit.bukkitDir)
        paperClone.set(objects.dirFrom(extension.workDir, "PaperClone"))
    }

    val setupSpigot by tasks.registering<SetupSpigot> {
        spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
    }

    val setupPaper by tasks.registering<SetupPaper> {
        dependsOn(applyServerPatches)
        paperServer.set(extension.serverProject)
    }

    val applySpigotCBPatches by tasks.registering<ApplyServerSourceAndNmsPatches> {
        craftBukkitDir.set(cloneRepos.flatMap { it.craftBukkitClone })
        sourcePatches.set(extension.spigot.craftBukkitPatchDir)
        initialVanillaSpigotPatch.set(setupSpigot.flatMap { it.initialVanillaSpigotPatch })
        initialCraftBukkitSpigotPatch.set(setupSpigot.flatMap { it.initialCraftBukkitSpigotPatch })
        nmsPatches.set(setupSpigot.flatMap { it.spigotNmsPatches })
        outputNmsPatches.set(objects.dirFrom(objects.dirFrom(craftBukkitDir, "nms-patches"), "spigot"))
    }

    val applyRemapSourcePatch by tasks.registering<ApplyInitialPaperPatch> {
        craftBukkitDir.set(applySpigotCBPatches.flatMap { it.outputDir })
        initialPatch.set(applyServerPatches.flatMap { it.remapSourcesPatch })
        caseOnlyClassNameChanges.set(cleanupSourceMappings.flatMap { it.caseOnlyNameChanges })
    }

    val remapCraftBukkitFilePatches by tasks.registering<RemapCraftBukkitPatches> {
        dependsOn(applyRemapSourcePatch)
        craftBukkitDir.set(applyRemapSourcePatch.flatMap { it.craftBukkitDir })
        decompiledJar.set(decompileJar.flatMap { it.outputJar })
    }

    val rewriteCraftBukkitSpigotHistory by tasks.registering<RewriteCommits> {
        repoDir.set(remapCraftBukkitFilePatches.flatMap { it.outputDir })
        paths.value(listOf(".gitignore", "pom.xml"))
        pathGlobs.value(listOf("nms-patches/**/*.*", "src/**/*.*"))
        commitCallback.value(RewriteCommits.CRAFTBUKKIT_CALLBACK)
    }

    val applyMcDevSourcePatch by tasks.registering<ApplyInitialPaperPatch> {
        craftBukkitDir.set(rewriteCraftBukkitSpigotHistory.flatMap { it.outputDir })
        initialPatch.set(applyServerPatches.flatMap { it.mcDevSourcesPatch })
        caseOnlyClassNameChanges.set(cleanupSourceMappings.flatMap { it.caseOnlyNameChanges })
        additionalExcludes.value(listOf("CONTRIBUTING.md", "README.md", "applyPatches.sh", "makePatches.sh"))
    }

    val applyPaperServerPatches by tasks.registering<ApplyServerSourceAndNmsPatches> {
        craftBukkitDir.set(applyMcDevSourcePatch.flatMap { it.outputDir })
        sourcePatches.set(extension.paper.spigotServerPatchDir)
        excludes.value(listOf("net/minecraft/**"))
        nmsPatches.set(setupPaper.flatMap { it.paperNmsPatches })
        outputNmsPatches.set(objects.dirFrom(objects.dirFrom(craftBukkitDir, "nms-patches"), "paper"))
    }

    val finalizeServerHistory by tasks.registering<MoveCommits> {
        repoDir.set(applyPaperServerPatches.flatMap { it.outputDir })
        toDir.value("paper-server")
    }

    val applySpigotApiPatches by tasks.registering<ApplyApiPatches> {
        bukkitDir.set(cloneRepos.flatMap { it.bukkitClone })
        patchesDir.set(extension.spigot.bukkitPatchDir)
    }

    val rewriteBukkitSpigotHistory by tasks.registering<RewriteCommits> {
        repoDir.set(applySpigotApiPatches.flatMap { it.outputDir })
        paths.value(listOf(".gitignore", "pom.xml", "README.md", "LICENSE.txt", "checkstyle.xml"))
        pathGlobs.value(listOf("src/**/*.*"))
        commitCallback.value(RewriteCommits.BUKKIT_CALLBACK)
    }

    val applyPaperApiPatches by tasks.registering<ApplyApiPatches> {
        bukkitDir.set(rewriteBukkitSpigotHistory.flatMap { it.outputDir })
        patchesDir.set(extension.paper.spigotApiPatchDir)
        unneededFiles.set(listOf("README.md", "checkstyle.xml"))
    }

    val finalizeApiHistory by tasks.registering<MoveCommits> {
        repoDir.set(applyPaperApiPatches.flatMap { it.outputDir })
        toDir.value("paper-api")
    }

    val rewriteAikarPaperHistory by tasks.registering<RewriteCommits> {
        repoDir.set(cloneRepos.flatMap { it.paperClone })
        commitCallback.value(RewriteCommits.PAPER_CALLBACK)
    }

    val rewriteSpigotPaperHistory by tasks.registering<RewritePartialPaperHistory> {
        paperDir.set(rewriteAikarPaperHistory.flatMap { it.outputDir })
    }

    val finalizePaperHistory by tasks.registering<FinalizePaperHistory> {
        paperDir.set(rewriteSpigotPaperHistory.flatMap { it.outputDir })
        deletions.value(listOf("work", "patches", ".gitmodules"))
    }

    val mergeGitRepos by tasks.registering<MergeGitRepos> {
        cloneBranch.set(cloneBranchName)
        bukkitDir.set(finalizeApiHistory.flatMap { it.outputDir })
        craftBukkitDir.set(finalizeServerHistory.flatMap { it.outputDir })
        paperDir.set(finalizePaperHistory.flatMap { it.outputDir })
        outputDir.set(extension.superDir)
    }
}
