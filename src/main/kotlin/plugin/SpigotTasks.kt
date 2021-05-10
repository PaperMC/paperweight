package io.papermc.paperweight.plugin

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.AddAdditionalSpigotMappings
import io.papermc.paperweight.tasks.ApplyDiffPatches
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.ApplyRawDiffPatches
import io.papermc.paperweight.tasks.CleanupMappings
import io.papermc.paperweight.tasks.DownloadSpigotDependencies
import io.papermc.paperweight.tasks.FilterSpigotExcludes
import io.papermc.paperweight.tasks.GenerateSpigotMappings
import io.papermc.paperweight.tasks.InspectVanillaJar
import io.papermc.paperweight.tasks.MergeAccessTransforms
import io.papermc.paperweight.tasks.PatchMappings
import io.papermc.paperweight.tasks.RemapAccessTransform
import io.papermc.paperweight.tasks.RemapSources
import io.papermc.paperweight.tasks.RemapSpigotAt
import io.papermc.paperweight.tasks.SpigotDecompileJar
import io.papermc.paperweight.tasks.SpigotRemapJar
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.download
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.registering
import java.io.File
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer

@Suppress("MemberVisibilityCanBePrivate")
open class SpigotTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: File = project.layout.cache,
    extension: PaperweightExtension = project.ext,
    downloadService: Provider<DownloadService> = project.download,
) : VanillaTasks(project) {

    val addAdditionalSpigotMappings by tasks.registering<AddAdditionalSpigotMappings> {
        classSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
        memberSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
        additionalClassEntriesSrg.set(extension.paper.additionalSpigotClassMappings)
        additionalMemberEntriesSrg.set(extension.paper.additionalSpigotMemberMappings)
    }

    val inspectVanillaJar by tasks.registering<InspectVanillaJar> {
        inputJar.set(downloadServerJar.flatMap { it.outputJar })
        librariesDir.set(downloadMcLibraries.flatMap { it.outputDir })
        mcLibraries.set(setupMcLibraries.flatMap { it.outputFile })

        serverLibraries.set(cache.resolve(Constants.SERVER_LIBRARIES))
    }

    val generateSpigotMappings by tasks.registering<GenerateSpigotMappings> {
        classMappings.set(addAdditionalSpigotMappings.flatMap { it.outputClassSrg })
        memberMappings.set(addAdditionalSpigotMappings.flatMap { it.outputMemberSrg })
        packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))

        loggerFields.set(inspectVanillaJar.flatMap { it.loggerFile })
        syntheticMethods.set(inspectVanillaJar.flatMap { it.syntheticMethods })

        sourceMappings.set(generateMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(Constants.SPIGOT_MOJANG_YARN_MAPPINGS))
    }

    val spigotRemapJar by tasks.registering<SpigotRemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        classMappings.set(addAdditionalSpigotMappings.flatMap { it.outputClassSrg })
        memberMappings.set(addAdditionalSpigotMappings.flatMap { it.outputMemberSrg })
        packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))
        accessTransformers.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.accessTransforms }))

        workDirName.set(extension.craftBukkit.buildDataInfo.asFile.map { it.parentFile.parentFile.name })

        specialSourceJar.set(extension.craftBukkit.specialSourceJar)
        specialSource2Jar.set(extension.craftBukkit.specialSource2Jar)

        classMapCommand.set(buildDataInfo.map { it.classMapCommand })
        memberMapCommand.set(buildDataInfo.map { it.memberMapCommand })
        finalMapCommand.set(buildDataInfo.map { it.finalMapCommand })
    }

    val cleanupMappings by tasks.registering<CleanupMappings> {
        sourceJar.set(spigotRemapJar.flatMap { it.outputJar })
        librariesDir.set(downloadMcLibraries.flatMap { it.outputDir })
        inputMappings.set(generateSpigotMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(Constants.CLEANED_SPIGOT_MOJANG_YARN_MAPPINGS))
    }

    val patchMappings by tasks.registering<PatchMappings> {
        inputMappings.set(cleanupMappings.flatMap { it.outputMappings })
        patch.set(extension.paper.mappingsPatch)

        outputMappings.set(cache.resolve(Constants.PATCHED_SPIGOT_MOJANG_YARN_MAPPINGS))
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
        inputDir.set(extension.craftBukkit.patchDir)
        patchDir.set(extension.paper.craftBukkitPatchPatchesDir)
    }

    val patchCraftBukkit by tasks.registering<ApplyDiffPatches> {
        sourceJar.set(spigotDecompileJar.flatMap { it.outputJar })
        cleanDirPath.set("net/minecraft")
        branch.set("patched")
        patchZip.set(patchCraftBukkitPatches.flatMap { it.outputZip })

        outputDir.set(extension.craftBukkit.craftBukkitDir)
    }

    val patchSpigotApiPatches by tasks.registering<ApplyRawDiffPatches> {
        inputDir.set(extension.spigot.bukkitPatchDir)
        patchDir.set(extension.paper.spigotApiPatchPatchesDir)
    }

    val patchSpigotServerPatches by tasks.registering<ApplyRawDiffPatches> {
        inputDir.set(extension.spigot.craftBukkitPatchDir)
        patchDir.set(extension.paper.spigotServerPatchPatchesDir)
    }

    val patchSpigotApi by tasks.registering<ApplyGitPatches> {
        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(extension.craftBukkit.bukkitDir)
        patchZip.set(patchSpigotApiPatches.flatMap { it.outputZip })

        outputDir.set(extension.spigot.spigotApiDir)
    }

    val patchSpigotServer by tasks.registering<ApplyGitPatches> {
        branch.set(patchCraftBukkit.flatMap { it.branch })
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
        outputDir.set(cache.resolve(Constants.SPIGOT_JARS_PATH))

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
        mappings.set(patchMappings.flatMap { it.outputMappings })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
        vanillaRemappedSpigotJar.set(filterSpigotExcludes.flatMap { it.outputZip })
        spigotDeps.set(downloadSpigotDependencies.flatMap { it.outputDir })
    }

    val remapGeneratedAt by tasks.registering<RemapAccessTransform> {
        inputFile.set(remapSpigotSources.flatMap { it.generatedAt })
        mappings.set(patchMappings.flatMap { it.outputMappings })
    }

    val mergeGeneratedAts by tasks.registering<MergeAccessTransforms> {
        inputFiles.add(remapGeneratedAt.flatMap { it.outputFile })
        inputFiles.add(remapSpigotAt.flatMap { it.outputFile })
    }
}
