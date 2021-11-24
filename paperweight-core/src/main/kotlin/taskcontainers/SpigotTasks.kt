/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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
open class SpigotTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
    downloadService: Provider<DownloadService> = project.download,
) : VanillaTasks(project) {

    val addAdditionalSpigotMappings by tasks.registering<AddAdditionalSpigotMappings> {
        dependsOn(initSubmodules)
        classSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
        additionalClassEntriesSrg.set(extension.paper.additionalSpigotClassMappings.fileExists(project))
    }

    val generateSpigotMappings by tasks.registering<GenerateSpigotMappings> {
        classMappings.set(addAdditionalSpigotMappings.flatMap { it.outputClassSrg })

        sourceMappings.set(generateMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(SPIGOT_MOJANG_YARN_MAPPINGS))
        notchToSpigotMappings.set(cache.resolve(OBF_SPIGOT_MAPPINGS))
        spigotMemberMappings.set(cache.resolve(SPIGOT_MEMBER_MAPPINGS))
    }

    val spigotRemapJar by tasks.registering<SpigotRemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        classMappings.set(addAdditionalSpigotMappings.flatMap { it.outputClassSrg })
        accessTransformers.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.accessTransforms }))

        memberMappings.set(generateSpigotMappings.flatMap { it.spigotMemberMappings })

        mcVersion.set(extension.minecraftVersion)

        workDirName.set(extension.craftBukkit.buildDataInfo.asFile.map { it.parentFile.parentFile.name })

        specialSourceJar.set(extension.craftBukkit.specialSourceJar)
        specialSource2Jar.set(extension.craftBukkit.specialSource2Jar)

        classMapCommand.set(buildDataInfo.map { it.classMapCommand })
        memberMapCommand.set(buildDataInfo.map { it.memberMapCommand })
        finalMapCommand.set(buildDataInfo.map { it.finalMapCommand })
    }

    val cleanupMappings by tasks.registering<CleanupMappings> {
        sourceJar.set(spigotRemapJar.flatMap { it.outputJar })
        libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })
        inputMappings.set(generateSpigotMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(CLEANED_SPIGOT_MOJANG_YARN_MAPPINGS))
    }

    val patchMappings by tasks.registering<PatchMappings> {
        inputMappings.set(cleanupMappings.flatMap { it.outputMappings })
        patch.set(extension.paper.mappingsPatch.fileExists(project))

        fromNamespace.set(SPIGOT_NAMESPACE)
        toNamespace.set(DEOBF_NAMESPACE)

        outputMappings.set(cache.resolve(PATCHED_SPIGOT_MOJANG_YARN_MAPPINGS))
    }

    val cleanupSourceMappings by tasks.registering<CleanupSourceMappings> {
        sourceJar.set(spigotRemapJar.flatMap { it.outputJar })
        libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })
        inputMappings.set(patchMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(PATCHED_SPIGOT_MOJANG_YARN_SOURCE_MAPPINGS))
    }

    val filterSpigotExcludes by tasks.registering<FilterSpigotExcludes> {
        inputZip.set(spigotRemapJar.flatMap { it.outputJar })
        excludesFile.set(extension.craftBukkit.excludesFile)
    }

    val spigotDecompileJar by tasks.registering<SpigotDecompileJar> {
        inputJar.set(filterSpigotExcludes.flatMap { it.outputZip })
        fernFlowerJar.set(extension.craftBukkit.fernFlowerJar)
        decompileCommand.set(buildDataInfo.map { it.decompileCommand })
    }

    val patchCraftBukkitPatches by tasks.registering<ApplyRawDiffPatches> {
        dependsOn(initSubmodules)
        inputDir.set(extension.craftBukkit.patchDir)
        patchDir.set(extension.paper.craftBukkitPatchPatchesDir.fileExists(project))
    }

    val patchCraftBukkit by tasks.registering<ApplyCraftBukkitPatches> {
        sourceJar.set(spigotDecompileJar.flatMap { it.outputJar })
        cleanDirPath.set("net/minecraft")
        branch.set("patched")
        patchZip.set(patchCraftBukkitPatches.flatMap { it.outputZip })
        craftBukkitDir.set(extension.craftBukkit.craftBukkitDir)
    }

    val patchSpigotApiPatches by tasks.registering<ApplyRawDiffPatches> {
        dependsOn(initSubmodules)
        inputDir.set(extension.spigot.bukkitPatchDir)
        patchDir.set(extension.paper.spigotApiPatchPatchesDir.fileExists(project))
    }

    val patchSpigotServerPatches by tasks.registering<ApplyRawDiffPatches> {
        dependsOn(initSubmodules)
        inputDir.set(extension.spigot.craftBukkitPatchDir)
        patchDir.set(extension.paper.spigotServerPatchPatchesDir.fileExists(project))
    }

    val patchSpigotApi by tasks.registering<ApplyGitPatches> {
        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(extension.craftBukkit.bukkitDir)
        patchZip.set(patchSpigotApiPatches.flatMap { it.outputZip })

        outputDir.set(extension.spigot.spigotApiDir)
    }

    val patchSpigotServer by tasks.registering<ApplyGitPatches> {
        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(patchCraftBukkit.flatMap { it.outputDir })
        patchZip.set(patchSpigotServerPatches.flatMap { it.outputZip })

        outputDir.set(extension.spigot.spigotServerDir)
    }

    val patchSpigot by tasks.registering<Task> {
        dependsOn(patchSpigotApi, patchSpigotServer)
    }

    val downloadSpigotDependencies by tasks.registering<DownloadSpigotDependencies> {
        dependsOn(patchSpigot)
        apiPom.set(patchSpigotApi.flatMap { it.outputDir.file("pom.xml") })
        serverPom.set(patchSpigotServer.flatMap { it.outputDir.file("pom.xml") })
        outputDir.set(cache.resolve(SPIGOT_JARS_PATH))

        downloader.set(downloadService)
    }

    val remapSpigotAt by tasks.registering<RemapSpigotAt> {
        inputJar.set(spigotRemapJar.flatMap { it.outputJar })
        mapping.set(patchMappings.flatMap { it.outputMappings })
        spigotAt.set(extension.craftBukkit.atFile)
    }

    @Suppress("DuplicatedCode")
    val remapSpigotSources by tasks.registering<RemapSources> {
        spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
        spigotApiDir.set(patchSpigotApi.flatMap { it.outputDir })
        mappings.set(cleanupSourceMappings.flatMap { it.outputMappings })
        vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
        mojangMappedVanillaJar.set(fixJar.flatMap { it.outputJar })
        vanillaRemappedSpigotJar.set(filterSpigotExcludes.flatMap { it.outputZip })
        spigotDeps.from(downloadSpigotDependencies.map { it.outputDir.asFileTree })
        additionalAts.set(extension.paper.additionalAts.fileExists(project))
    }

    val remapGeneratedAt by tasks.registering<RemapAccessTransform> {
        inputFile.set(remapSpigotSources.flatMap { it.generatedAt })
        mappings.set(patchMappings.flatMap { it.outputMappings })
    }

    val mergeGeneratedAts by tasks.registering<MergeAccessTransforms> {
        firstFile.set(remapGeneratedAt.flatMap { it.outputFile })
        secondFile.set(remapSpigotAt.flatMap { it.outputFile })
    }
}
