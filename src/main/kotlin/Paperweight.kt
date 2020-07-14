/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

@file:Suppress("DuplicatedCode")

package io.papermc.paperweight

import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.AddMissingSpigotClassMappings
import io.papermc.paperweight.tasks.ApplyAccessTransform
import io.papermc.paperweight.tasks.ApplyDiffPatches
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.DecompileVanillaJar
import io.papermc.paperweight.tasks.DownloadServerJar
import io.papermc.paperweight.tasks.ExtractMcpData
import io.papermc.paperweight.tasks.ExtractMcpMappings
import io.papermc.paperweight.tasks.FilterExcludes
import io.papermc.paperweight.tasks.GatherBuildData
import io.papermc.paperweight.tasks.GenerateSpigotSrgs
import io.papermc.paperweight.tasks.GenerateSrgs
import io.papermc.paperweight.tasks.GetRemoteJsons
import io.papermc.paperweight.tasks.PatchMcpCsv
import io.papermc.paperweight.tasks.RemapSources
import io.papermc.paperweight.tasks.RemapVanillaJarSrg
import io.papermc.paperweight.tasks.RunForgeFlower
import io.papermc.paperweight.tasks.RunMcInjector
import io.papermc.paperweight.tasks.SetupMcpDependencies
import io.papermc.paperweight.tasks.SetupSpigotDependencies
import io.papermc.paperweight.tasks.WriteLibrariesFile
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.tasks.RemapSrgSources
import io.papermc.paperweight.tasks.RemapVanillaJarSpigot
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.ext
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.register
import util.BuildDataInfo
import java.io.File

class Paperweight : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightExtension::class.java, target)

        createConfigurations(target)
        setupMcpDeps(target)

        createTasks(target)

        target.tasks.register("cleanCache").configure {
            destroyables.register(target.cache)
            doLast {
                target.delete(target.cache)
            }
        }
    }

    private fun createConfigurations(project: Project) {
        project.repositories.apply {
            maven("https://oss.sonatype.org/content/repositories/snapshots/")
            maven("https://hub.spigotmc.org/nexus/content/groups/public/")
            maven {
                name = "forge"
                url = project.uri(Constants.FORGE_MAVEN_URL)
                metadataSources {
                    artifact()
                }
            }
            mavenCentral()
            maven {
                name = "minecraft"
                url = project.uri(Constants.MC_LIBRARY_URL)
            }
        }

        project.configurations.register(Constants.MCP_MAPPINGS_CONFIG)
        project.configurations.register(Constants.MCP_DATA_CONFIG)
        project.configurations.register(Constants.SPIGOT_DEP_CONFIG)
        project.configurations.create(Constants.MINECRAFT_DEP_CONFIG)
        project.configurations.register(Constants.FORGE_FLOWER_CONFIG)
        project.configurations.create(Constants.MCINJECT_CONFIG)
    }

    private fun setupMcpDeps(project: Project) {
        project.dependencies.add(Constants.MCP_DATA_CONFIG, project.provider {
            mapOf(
                "group" to "de.oceanlabs.mcp",
                "name" to "mcp_config",
                "version" to "${project.ext.mcpMinecraftVersion.get()}-${project.ext.mcpVersion.get()}",
                "ext" to "zip"
            )
        })

        project.dependencies.add(Constants.MCP_MAPPINGS_CONFIG, project.provider {
            mapOf(
                "group" to "de.oceanlabs.mcp",
                "name" to "mcp_${project.ext.mcpMappingsChannel.get()}",
                "version" to project.ext.mcpMappingsVersion.get(),
                "ext" to "zip"
            )
        })
    }

    // Types are specified in this method to hopefully improve editor performance a little, though I don't think it helps
    private fun createTasks(project: Project) {
        val cache: File = project.cache
        val extension: PaperweightExtension = project.ext

        val initGitSubmodules: TaskProvider<Task> = project.tasks.register("initGitSubmodules") {
            outputs.upToDateWhen { false }
            doLast {
                Git(project.projectDir)("submodule", "update", "--init").execute()
            }
        }
        val gatherBuildData: TaskProvider<GatherBuildData> = project.tasks.register<GatherBuildData>("gatherBuildData") {
            dependsOn(initGitSubmodules)
            buildDataInfoFile.set(extension.craftBukkit.buildDataInfo)
        }
        val buildDataInfo: Provider<BuildDataInfo> = gatherBuildData.flatMap { it.buildDataInfo }

        val extractMcpData: TaskProvider<ExtractMcpData> = project.tasks.register<ExtractMcpData>("extractMcpData") {
            config.set(Constants.MCP_DATA_CONFIG)
            outputDir.set(cache.resolve(Constants.MCP_DATA_DIR))
        }

        val setupMcpDependencies: TaskProvider<SetupMcpDependencies> = project.tasks.register<SetupMcpDependencies>("setupMcpDependencies") {
            configFile.set(extractMcpData.flatMap { it.configJson })
            forgeFlowerConfig.set(Constants.FORGE_FLOWER_CONFIG)
            mcInjectorConfig.set(Constants.MCINJECT_CONFIG)
        }

        val extractMcpMappings: TaskProvider<ExtractMcpMappings> = project.tasks.register<ExtractMcpMappings>("extractMcpMappings") {
            config.set(Constants.MCP_MAPPINGS_CONFIG)
            outputDir.set(cache.resolve(Constants.MCP_MAPPINGS_DIR))
        }

        val getRemoteJsons: TaskProvider<GetRemoteJsons> = project.tasks.register<GetRemoteJsons>("getRemoteJsons") {
            config.set(Constants.MINECRAFT_DEP_CONFIG)
        }

        val mcpRewrites: TaskProvider<PatchMcpCsv> = project.tasks.register<PatchMcpCsv>("mcpRewrites") {
            fieldsCsv.set(extractMcpMappings.flatMap { it.fieldsCsv })
            methodsCsv.set(extractMcpMappings.flatMap { it.methodsCsv })
            paramsCsv.set(extractMcpMappings.flatMap { it.paramsCsv })
            changesFile.set(extension.paper.mcpRewritesFile)

            paperFieldCsv.set(cache.resolve(Constants.PAPER_FIELDS_CSV))
            paperMethodCsv.set(cache.resolve(Constants.PAPER_METHODS_CSV))
            paperParamCsv.set(cache.resolve(Constants.PAPER_PARAMS_CSV))
        }

        val generateSrgs: TaskProvider<GenerateSrgs> = project.tasks.register<GenerateSrgs>("generateSrgs") {
            configFile.set(extractMcpData.flatMap { it.configJson })
            methodsCsv.set(mcpRewrites.flatMap { it.paperMethodCsv })
            fieldsCsv.set(mcpRewrites.flatMap { it.paperFieldCsv })
            extraNotchSrgMappings.set(extension.paper.extraNotchSrgMappings)

            notchToSrg.set(cache.resolve(Constants.NOTCH_TO_SRG))
            notchToMcp.set(cache.resolve(Constants.NOTCH_TO_MCP))
            srgToNotch.set(cache.resolve(Constants.SRG_TO_NOTCH))
            srgToMcp.set(cache.resolve(Constants.SRG_TO_MCP))
            mcpToNotch.set(cache.resolve(Constants.MCP_TO_NOTCH))
            mcpToSrg.set(cache.resolve(Constants.MCP_TO_SRG))
        }

        val addMissingSpigotClassMappings: TaskProvider<AddMissingSpigotClassMappings> = project.tasks.register<AddMissingSpigotClassMappings>("addMissingSpigotClassMappings") {
            classSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            missingClassEntriesSrg.set(extension.paper.missingClassEntriesSrgFile)
            missingMemberEntriesSrg.set(extension.paper.missingMemberEntriesSrgFile)
        }

        val generateSpigotSrgs: TaskProvider<GenerateSpigotSrgs> = project.tasks.register<GenerateSpigotSrgs>("generateSpigotSrgs") {
            notchToSrg.set(generateSrgs.flatMap { it.notchToSrg })
            srgToMcp.set(generateSrgs.flatMap { it.srgToMcp })
            classMappings.set(addMissingSpigotClassMappings.flatMap { it.outputClassSrg })
            memberMappings.set(addMissingSpigotClassMappings.flatMap { it.outputMemberSrg })
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))
            extraSpigotSrgMappings.set(extension.paper.extraSpigotSrgMappings)

            spigotToSrg.set(cache.resolve(Constants.SPIGOT_TO_SRG))
            spigotToMcp.set(cache.resolve(Constants.SPIGOT_TO_MCP))
            spigotToNotch.set(cache.resolve(Constants.SPIGOT_TO_NOTCH))
            srgToSpigot.set(cache.resolve(Constants.SRG_TO_SPIGOT))
            mcpToSpigot.set(cache.resolve(Constants.MCP_TO_SPIGOT))
            notchToSpigot.set(cache.resolve(Constants.NOTCH_TO_SPIGOT))
        }

        val downloadServerJar: TaskProvider<DownloadServerJar> = project.tasks.register<DownloadServerJar>("downloadServerJar") {
            dependsOn(gatherBuildData)
            downloadUrl.set(buildDataInfo.map { it.serverUrl })
            hash.set(buildDataInfo.map { it.minecraftHash })
        }

        val filterVanillaJar: TaskProvider<Zip> = project.tasks.register<Zip>("filterVanillaJar") {
            dependsOn(downloadServerJar) // the from() block below doesn't set up this dependency
            archiveFileName.set("filterVanillaJar.jar")
            destinationDirectory.set(cache.resolve(Constants.TASK_CACHE))

            from(project.zipTree(downloadServerJar.flatMap { it.outputJar })) {
                include("/*.class")
                include("/net/minecraft/**")
            }
        }

        val remapVanillaJar: TaskProvider<RemapVanillaJarSpigot> = project.tasks.register<RemapVanillaJarSpigot>("remapVanillaJar") {
            inputJar.set(project.layout.file(filterVanillaJar.map { it.outputs.files.singleFile }))
            classMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))
            accessTransformers.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.accessTransforms }))

            workDirName.set(extension.craftBukkit.buildDataInfo.asFile.map { it.parentFile.parentFile.name })

            specialSourceJar.set(extension.craftBukkit.specialSourceJar)
            specialSource2Jar.set(extension.craftBukkit.specialSource2Jar)

            classMapCommand.set(buildDataInfo.map { it.classMapCommand })
            memberMapCommand.set(buildDataInfo.map { it.memberMapCommand })
            finalMapCommand.set(buildDataInfo.map { it.finalMapCommand })
        }

        val removeSpigotExcludes: TaskProvider<FilterExcludes> = project.tasks.register<FilterExcludes>("removeSpigotExcludes") {
            inputZip.set(remapVanillaJar.flatMap { it.outputJar })
            excludesFile.set(extension.craftBukkit.excludesFile)
        }

        val decompileVanillaJarSpigot: TaskProvider<DecompileVanillaJar> = project.tasks.register<DecompileVanillaJar>("decompileVanillaJarSpigot") {
            inputJar.set(removeSpigotExcludes.flatMap { it.outputZip })
            fernFlowerJar.set(extension.craftBukkit.fernFlowerJar)
            decompileCommand.set(buildDataInfo.map { it.decompileCommand })
        }

        val patchCraftBukkit: TaskProvider<ApplyDiffPatches> = project.tasks.register<ApplyDiffPatches>("patchCraftBukkit") {
            sourceJar.set(decompileVanillaJarSpigot.flatMap { it.outputJar })
            sourceBasePath.set("net/minecraft/server")
            branch.set("patched")
            patchDir.set(extension.craftBukkit.patchDir)

            outputDir.set(extension.craftBukkit.craftBukkitDir)
        }

        val patchSpigotApi: TaskProvider<ApplyGitPatches> = project.tasks.register<ApplyGitPatches>("patchSpigotApi") {
            branch.set("HEAD")
            upstreamBranch.set("upstream")
            upstream.set(extension.craftBukkit.bukkitDir)
            patchDir.set(extension.spigot.bukkitPatchDir)

            outputDir.set(extension.spigot.spigotApiDir)
        }

        val patchSpigotServer: TaskProvider<ApplyGitPatches> = project.tasks.register<ApplyGitPatches>("patchSpigotServer") {
            branch.set(patchCraftBukkit.flatMap { it.branch })
            upstreamBranch.set("upstream")
            upstream.set(patchCraftBukkit.flatMap { it.outputDir })
            patchDir.set(extension.spigot.craftBukkitPatchDir)

            outputDir.set(extension.spigot.spigotServerDir)
        }

        val patchSpigot: TaskProvider<Task> = project.tasks.register("patchSpigot") {
            dependsOn(patchSpigotApi, patchSpigotServer)
        }

        val setupSpigotDependencies: TaskProvider<SetupSpigotDependencies> = project.tasks.register<SetupSpigotDependencies>("setupSpigotDependencies") {
            dependsOn(patchSpigot)
            spigotApi.set(patchSpigotApi.flatMap { it.outputDir })
            spigotServer.set(patchSpigotServer.flatMap { it.outputDir })
            configurationName.set(Constants.SPIGOT_DEP_CONFIG)
        }

        val remapSpigotJarSrg: TaskProvider<RemapVanillaJarSrg> = project.tasks.register<RemapVanillaJarSrg>("remapSpigotJarSrg") {
            // Basing off of the Spigot jar lets us inherit the AT modifications Spigot does before decompile
            inputJar.set(remapVanillaJar.flatMap { it.outputJar })
            mappings.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
        }

        val remapSpigotSources: TaskProvider<RemapSources> = project.tasks.register<RemapSources>("remapSpigotSources") {
            dependsOn(setupSpigotDependencies)
            spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
            spigotApiDir.set(patchSpigotApi.flatMap { it.outputDir })
            mappings.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
            vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
            vanillaRemappedSpigotJar.set(removeSpigotExcludes.flatMap { it.outputZip })
            vanillaRemappedSrgJar.set(remapSpigotJarSrg.flatMap { it.outputJar })
            configuration.set(setupSpigotDependencies.flatMap { it.configurationName })
            configFile.set(extractMcpData.flatMap { it.configJson })
        }

        val fixVanillaJarAccess: TaskProvider<ApplyAccessTransform> = project.tasks.register<ApplyAccessTransform>("fixVanillaJarAccess") {
            inputJar.set(remapSpigotJarSrg.flatMap { it.outputJar })
            atFile.set(remapSpigotSources.flatMap { it.generatedAt })
            mapping.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
        }

        val remapSrgSourcesSpigot: TaskProvider<RemapSrgSources> = project.tasks.register<RemapSrgSources>("remapSrgSourcesSpigot") {
            inputZip.set(remapSpigotSources.flatMap { it.outputZip })
            methodsCsv.set(mcpRewrites.flatMap { it.methodsCsv })
            fieldsCsv.set(mcpRewrites.flatMap { it.fieldsCsv })
            paramsCsv.set(mcpRewrites.flatMap { it.paramsCsv })
        }

        val injectVanillaJarForge: TaskProvider<RunMcInjector> = project.tasks.register<RunMcInjector>("injectVanillaJarForge") {
            dependsOn(setupMcpDependencies)
            configuration.set(setupMcpDependencies.flatMap { it.mcInjectorConfig })
            inputJar.set(remapSpigotJarSrg.flatMap { it.outputJar })
            configFile.set(extractMcpData.flatMap { it.configJson })
        }

        val writeLibrariesFile: TaskProvider<WriteLibrariesFile> = project.tasks.register<WriteLibrariesFile>("writeLibrariesFile") {
            dependsOn(getRemoteJsons)
            config.set(getRemoteJsons.flatMap { it.config })
        }

        val decompileVanillaJarForge: TaskProvider<RunForgeFlower> = project.tasks.register<RunForgeFlower>("decompileVanillaJarForge") {
            dependsOn(setupMcpDependencies)
            configuration.set(setupMcpDependencies.flatMap { it.forgeFlowerConfig })
            inputJar.set(injectVanillaJarForge.flatMap { it.outputJar })
            libraries.set(writeLibrariesFile.flatMap { it.outputFile })
            configFile.set(extractMcpData.flatMap { it.configJson })
        }

//        val applyMcpPatches: TaskProvider<ApplyMcpPatches> = project.tasks.register<ApplyMcpPatches>("applyMcpPatches") {
//            inputZips.add(ZipTarget.base(decompileVanillaJarForge.flatMap { it.outputJar }))
//            serverPatchDir.set(extractMcpData.flatMap { it.patches })
//        }
//
//        val remapSrgSourcesSpigotVanilla: TaskProvider<RemapSrgSources> = project.tasks.register<RemapSrgSources>("remapSrgSourcesSpigotVanilla") {
//            inputZips.add(ZipTarget.base(applyMcpPatches.flatMap { outputZip }))
//            methodsCsv.set(mcpRewrites.flatMap { methodsCsv })
//            fieldsCsv.set(mcpRewrites.flatMap { fieldsCsv })
//            paramsCsv.set(mcpRewrites.flatMap { paramsCsv })
//        }
    }
}
