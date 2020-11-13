/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
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

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.AddMissingSpigotClassMappings
import io.papermc.paperweight.tasks.ApplyDiffPatches
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.ApplyMcpPatches
import io.papermc.paperweight.tasks.ApplyPaperPatches
import io.papermc.paperweight.tasks.ApplySourceAt
import io.papermc.paperweight.tasks.DecompileVanillaJar
import io.papermc.paperweight.tasks.DownloadMcLibraries
import io.papermc.paperweight.tasks.DownloadMcpFiles
import io.papermc.paperweight.tasks.DownloadMcpTools
import io.papermc.paperweight.tasks.DownloadServerJar
import io.papermc.paperweight.tasks.DownloadSpigotDependencies
import io.papermc.paperweight.tasks.DownloadTask
import io.papermc.paperweight.tasks.ExtractMappings
import io.papermc.paperweight.tasks.ExtractMcp
import io.papermc.paperweight.tasks.Filter
import io.papermc.paperweight.tasks.FilterExcludes
import io.papermc.paperweight.tasks.GenerateSpigotSrgs
import io.papermc.paperweight.tasks.GenerateSrgs
import io.papermc.paperweight.tasks.InspectVanillaJar
import io.papermc.paperweight.tasks.Merge
import io.papermc.paperweight.tasks.MergeAccessTransforms
import io.papermc.paperweight.tasks.PatchMcpCsv
import io.papermc.paperweight.tasks.RemapAccessTransform
import io.papermc.paperweight.tasks.RemapSpigotAt
import io.papermc.paperweight.tasks.RemapVanillaJarSpigot
import io.papermc.paperweight.tasks.RunForgeFlower
import io.papermc.paperweight.tasks.RunMcInjector
import io.papermc.paperweight.tasks.RunSpecialSource
import io.papermc.paperweight.tasks.SetupMcLibraries
import io.papermc.paperweight.tasks.WriteLibrariesFile
import io.papermc.paperweight.tasks.patchremap.ApplyAccessTransform
import io.papermc.paperweight.tasks.patchremap.RemapPatches
import io.papermc.paperweight.tasks.sourceremap.RemapSources
import io.papermc.paperweight.util.BuildDataInfo
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.MinecraftManifest
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.contents
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.fromJson
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.registering
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.register
import java.io.File

class Paperweight : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightExtension::class.java, target.objects, target.layout)

        target.tasks.register<Delete>("cleanCache") {
            delete(target.layout.cache)
        }

        // Make sure the submodules are initialized
        Git(target.projectDir)("submodule", "update", "--init").execute()

        target.repositories.apply {
            mavenCentral()
            // Both of these are needed for Spigot
            maven("https://oss.sonatype.org/content/repositories/snapshots/")
            maven("https://hub.spigotmc.org/nexus/content/groups/public/")
        }

        target.createTasks()
    }

    private fun Project.createTasks() {
        val extension = ext

        val initialTasks = createInitialTasks()
        val generalTasks = createGeneralTasks()
        val mcpTasks = createMcpTasks(initialTasks, generalTasks)
        val spigotTasks = createSpigotTasks(initialTasks, generalTasks, mcpTasks)

        createPatchRemapTasks(initialTasks, generalTasks, mcpTasks, spigotTasks)

        val applySourceAt by tasks.registering<ApplySourceAt> {
            inputZip.set(mcpTasks.applyMcpPatches.flatMap { it.outputZip })
            vanillaJar.set(generalTasks.downloadServerJar.flatMap { it.outputJar })
            vanillaRemappedSrgJar.set(mcpTasks.remapVanillaJarSrg.flatMap { it.outputJar })
            atFile.set(spigotTasks.mergeGeneratedAts.flatMap { it.outputFile })
        }

        val mergeRemappedSources by tasks.registering<Merge> {
            inputJars.add(spigotTasks.remapSpigotSources.flatMap { it.outputZip })
            inputJars.add(applySourceAt.flatMap { it.outputZip })
        }

        val patchPaperApi by tasks.registering<ApplyGitPatches> {
            branch.set("HEAD")
            upstreamBranch.set("upstream")
            upstream.set(spigotTasks.patchSpigotApi.flatMap { it.outputDir })
            patchDir.set(extension.paper.spigotApiPatchDir)
            printOutput.set(true)

            outputDir.set(extension.paper.paperApiDir)
        }

        val patchPaperServer by tasks.registering<ApplyPaperPatches> {
            patchDir.set(extension.paper.spigotServerPatchDir)
            remappedSource.set(mergeRemappedSources.flatMap { it.outputJar })
            templateGitIgnore.set(layout.projectDirectory.file(".gitignore"))

            outputDir.set(extension.paper.paperServerDir)
        }

        val patchPaper by tasks.registering<Task> {
            dependsOn(patchPaperApi, patchPaperServer)
        }

        /*
         * Not bothering mapping away from SRG until everything is stable under SRG
         * Moving off of SRG will make things a lot more fragile
        val remapSrgSourcesSpigotVanilla by tasks.registering<RemapSrgSources> {
            inputZips.add(ZipTarget.base(applyMcpPatches.flatMap { outputZip }))
            methodsCsv.set(mcpRewrites.flatMap { methodsCsv })
            fieldsCsv.set(mcpRewrites.flatMap { fieldsCsv })
            paramsCsv.set(mcpRewrites.flatMap { paramsCsv })
        }
         */
    }

    // Shared task containers
    data class InitialTasks(
        val setupMcLibraries: TaskProvider<SetupMcLibraries>,
        val extractMcp: Provider<ExtractMcp>,
        val mcpMappings: Provider<ExtractMappings>,
        val downloadMcpTools: TaskProvider<DownloadMcpTools>
    )

    data class GeneralTasks(
        val buildDataInfo: Provider<BuildDataInfo>,
        val downloadServerJar: TaskProvider<DownloadServerJar>,
        val filterVanillaJar: TaskProvider<Filter>
    )

    data class McpTasks(
        val generateSrgs: TaskProvider<GenerateSrgs>,
        val remapVanillaJarSrg: TaskProvider<RunSpecialSource>,
        val applyMcpPatches: TaskProvider<ApplyMcpPatches>
    )

    data class SpigotTasks(
        val generateSpigotSrgs: TaskProvider<GenerateSpigotSrgs>,
        val decompileVanillaJarSpigot: TaskProvider<DecompileVanillaJar>,
        val patchSpigotApi: TaskProvider<ApplyGitPatches>,
        val patchSpigotServer: TaskProvider<ApplyGitPatches>,
        val remapSpigotSources: TaskProvider<RemapSources>,
        val mergeGeneratedAts: TaskProvider<MergeAccessTransforms>
    )

    private fun Project.createInitialTasks(): InitialTasks {
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext

        val downloadMcManifest by tasks.registering<DownloadTask> {
            url.set(Constants.MC_MANIFEST_URL)
            outputFile.set(cache.resolve(Constants.MC_MANIFEST))
        }

        val mcManifest = downloadMcManifest.flatMap { it.outputFile }.map { gson.fromJson<MinecraftManifest>(it) }

        val downloadMcVersionManifest by tasks.registering<DownloadTask> {
            url.set(mcManifest.zip(extension.minecraftVersion) { manifest, version ->
                manifest.versions.first { it.id == version }.url
            })
            outputFile.set(cache.resolve(Constants.VERSION_JSON))
        }

        val versionManifest = downloadMcVersionManifest.flatMap { it.outputFile }.map { gson.fromJson<JsonObject>(it) }

        val setupMcLibraries by tasks.registering<SetupMcLibraries> {
            dependencies.set(versionManifest.map { version ->
                version["libraries"].array.map { library ->
                    library["name"].string
                }.filter { !it.contains("lwjgl") } // we don't need these on the server
            })
            outputFile.set(cache.resolve(Constants.MC_LIBRARIES))
        }

        val downloadMcpFiles by tasks.registering<DownloadMcpFiles> {
            mcpMinecraftVersion.set(extension.mcpMinecraftVersion)
            mcpConfigVersion.set(extension.mcpConfigVersion)
            mcpMappingsChannel.set(extension.mcpMappingsChannel)
            mcpMappingsVersion.set(extension.mcpMappingsVersion)

            configZip.set(cache.resolve(Constants.MCP_ZIPS_PATH).resolve("McpConfig.zip"))
            mappingsZip.set(cache.resolve(Constants.MCP_ZIPS_PATH).resolve("McpMappings.zip"))
        }

        val extractMcpConfig by tasks.registering<ExtractMcp> {
            inputFile.set(downloadMcpFiles.flatMap { it.configZip })
            outputDir.set(cache.resolve(Constants.MCP_DATA_DIR))
        }
        val extractMcpMappings by tasks.registering<ExtractMappings> {
            inputFile.set(downloadMcpFiles.flatMap { it.mappingsZip })
            outputDir.set(cache.resolve(Constants.MCP_MAPPINGS_DIR))
        }

        val downloadMcpTools by tasks.registering<DownloadMcpTools> {
            configFile.set(extractMcpConfig.flatMap { it.configFile })

            val toolsPath = cache.resolve(Constants.MCP_TOOLS_PATH)
            forgeFlowerFile.set(toolsPath.resolve("ForgeFlower.jar"))
            mcInjectorFile.set(toolsPath.resolve("McInjector.jar"))
            specialSourceFile.set(toolsPath.resolve("SpecialSource.jar"))
        }

        return InitialTasks(
            setupMcLibraries,
            extractMcpConfig,
            extractMcpMappings,
            downloadMcpTools
        )
    }

    private fun Project.createGeneralTasks(): GeneralTasks {
        val buildDataInfo: Provider<BuildDataInfo> = contents(ext.craftBukkit.buildDataInfo) {
            gson.fromJson(it)
        }

        val downloadServerJar by tasks.registering<DownloadServerJar> {
            downloadUrl.set(buildDataInfo.map { it.serverUrl })
            hash.set(buildDataInfo.map { it.minecraftHash })
        }

        val filterVanillaJar by tasks.registering<Filter> {
            inputJar.set(downloadServerJar.flatMap { it.outputJar })
            includes.set(listOf("/*.class", "/net/minecraft/**"))
        }

        return GeneralTasks(buildDataInfo, downloadServerJar, filterVanillaJar)
    }

    private fun Project.createMcpTasks(initialTasks: InitialTasks, generalTasks: GeneralTasks): McpTasks {
        val filterVanillaJar: TaskProvider<Filter> = generalTasks.filterVanillaJar
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext

        val mcpRewrites by tasks.registering<PatchMcpCsv> {
            fieldsCsv.set(initialTasks.mcpMappings.flatMap { it.fieldsCsv })
            methodsCsv.set(initialTasks.mcpMappings.flatMap { it.methodsCsv })
            paramsCsv.set(initialTasks.mcpMappings.flatMap { it.paramsCsv })
            changesFile.set(extension.paper.mcpRewritesFile)

            paperFieldCsv.set(cache.resolve(Constants.PAPER_FIELDS_CSV))
            paperMethodCsv.set(cache.resolve(Constants.PAPER_METHODS_CSV))
            paperParamCsv.set(cache.resolve(Constants.PAPER_PARAMS_CSV))
        }

        val generateSrgs by tasks.registering<GenerateSrgs> {
            inSrg.set(initialTasks.extractMcp.flatMap { it.mappings })

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

        val remapVanillaJarSrg by tasks.registering<RunSpecialSource> {
            inputJar.set(filterVanillaJar.flatMap { it.outputJar })
            mappings.set(generateSrgs.flatMap { it.notchToSrg })

            executable.set(initialTasks.downloadMcpTools.flatMap { it.specialSourceFile })
            configFile.set(initialTasks.extractMcp.flatMap { it.configFile })
        }

        val injectVanillaJarSrg by tasks.registering<RunMcInjector> {
            executable.set(initialTasks.downloadMcpTools.flatMap { it.mcInjectorFile })
            configFile.set(initialTasks.extractMcp.flatMap { it.configFile })

            exceptions.set(initialTasks.extractMcp.flatMap { it.exceptions })
            access.set(initialTasks.extractMcp.flatMap { it.access })
            constructors.set(initialTasks.extractMcp.flatMap { it.constructors })

            inputJar.set(remapVanillaJarSrg.flatMap { it.outputJar })
        }

        val downloadMcLibraries by tasks.registering<DownloadMcLibraries> {
            mcLibrariesFile.set(initialTasks.setupMcLibraries.flatMap { it.outputFile })
            mcRepo.set(Constants.MC_LIBRARY_URL)
            outputDir.set(cache.resolve(Constants.MINECRAFT_JARS_PATH))
        }

        val writeLibrariesFile by tasks.registering<WriteLibrariesFile> {
            libraries.set(downloadMcLibraries.flatMap { it.outputDir })
        }

        val decompileVanillaJarSrg by tasks.registering<RunForgeFlower> {
            executable.set(initialTasks.downloadMcpTools.flatMap { it.forgeFlowerFile })
            configFile.set(initialTasks.extractMcp.flatMap { it.configFile })

            inputJar.set(injectVanillaJarSrg.flatMap { it.outputJar })
            libraries.set(writeLibrariesFile.flatMap { it.outputFile })
        }

        val applyMcpPatches by tasks.registering<ApplyMcpPatches> {
            inputZip.set(decompileVanillaJarSrg.flatMap { it.outputJar })
            serverPatchDir.set(initialTasks.extractMcp.flatMap { it.patchDir })
            configFile.set(cache.resolve(Constants.MCP_CONFIG_JSON))
        }

        return McpTasks(generateSrgs, remapVanillaJarSrg, applyMcpPatches)
    }

    private fun Project.createSpigotTasks(initialTasks: InitialTasks, generalTasks: GeneralTasks, mcpTasks: McpTasks): SpigotTasks {
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext

        val (buildDataInfo, downloadServerJar, filterVanillaJar) = generalTasks
        val (generateSrgs, _, _) = mcpTasks

        val addMissingSpigotClassMappings by tasks.registering<AddMissingSpigotClassMappings> {
            classSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            missingClassEntriesSrg.set(extension.paper.missingClassEntriesSrgFile)
            missingMemberEntriesSrg.set(extension.paper.missingMemberEntriesSrgFile)
        }

        val inspectVanillaJar by tasks.registering<InspectVanillaJar> {
            inputJar.set(downloadServerJar.flatMap { it.outputJar })
        }

        val generateSpigotSrgs by tasks.registering<GenerateSpigotSrgs> {
            notchToSrg.set(generateSrgs.flatMap { it.notchToSrg })
            srgToMcp.set(generateSrgs.flatMap { it.srgToMcp })
            classMappings.set(addMissingSpigotClassMappings.flatMap { it.outputClassSrg })
            memberMappings.set(addMissingSpigotClassMappings.flatMap { it.outputMemberSrg })
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))
            extraSpigotSrgMappings.set(extension.paper.extraSpigotSrgMappings)
            loggerFields.set(inspectVanillaJar.flatMap { it.outputFile })

            spigotToSrg.set(cache.resolve(Constants.SPIGOT_TO_SRG))
            spigotToMcp.set(cache.resolve(Constants.SPIGOT_TO_MCP))
            spigotToNotch.set(cache.resolve(Constants.SPIGOT_TO_NOTCH))
            srgToSpigot.set(cache.resolve(Constants.SRG_TO_SPIGOT))
            mcpToSpigot.set(cache.resolve(Constants.MCP_TO_SPIGOT))
            notchToSpigot.set(cache.resolve(Constants.NOTCH_TO_SPIGOT))
        }

        val remapVanillaJarSpigot by tasks.registering<RemapVanillaJarSpigot> {
            inputJar.set(filterVanillaJar.flatMap { it.outputJar })
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

        val removeSpigotExcludes by tasks.registering<FilterExcludes> {
            inputZip.set(remapVanillaJarSpigot.flatMap { it.outputJar })
            excludesFile.set(extension.craftBukkit.excludesFile)
        }

        val decompileVanillaJarSpigot by tasks.registering<DecompileVanillaJar> {
            inputJar.set(removeSpigotExcludes.flatMap { it.outputZip })
            fernFlowerJar.set(extension.craftBukkit.fernFlowerJar)
            decompileCommand.set(buildDataInfo.map { it.decompileCommand })
        }

        val patchCraftBukkit by tasks.registering<ApplyDiffPatches> {
            sourceJar.set(decompileVanillaJarSpigot.flatMap { it.outputJar })
            sourceBasePath.set("net/minecraft/server")
            branch.set("patched")
            patchDir.set(extension.craftBukkit.patchDir)

            outputDir.set(extension.craftBukkit.craftBukkitDir)
        }

        val patchSpigotApi by tasks.registering<ApplyGitPatches> {
            branch.set("HEAD")
            upstreamBranch.set("upstream")
            upstream.set(extension.craftBukkit.bukkitDir)
            patchDir.set(extension.spigot.bukkitPatchDir)

            outputDir.set(extension.spigot.spigotApiDir)
        }

        val patchSpigotServer by tasks.registering<ApplyGitPatches> {
            branch.set(patchCraftBukkit.flatMap { it.branch })
            upstreamBranch.set("upstream")
            upstream.set(patchCraftBukkit.flatMap { it.outputDir })
            patchDir.set(extension.spigot.craftBukkitPatchDir)

            outputDir.set(extension.spigot.spigotServerDir)
        }

        val patchSpigot by tasks.registering<Task> {
            dependsOn(patchSpigotApi, patchSpigotServer)
        }

        val downloadSpigotDependencies by tasks.registering<DownloadSpigotDependencies> {
            dependsOn(patchSpigot)
            apiPom.set(patchSpigotApi.flatMap { it.outputDir.file("pom.xml") })
            serverPom.set(patchSpigotServer.flatMap { it.outputDir.file("pom.xml") })
            apiOutputDir.set(cache.resolve(Constants.SPIGOT_API_JARS_PATH))
            serverOutputDir.set(cache.resolve(Constants.SPIGOT_SERVER_JARS_PATH))
        }

        val remapSpigotAt by tasks.registering<RemapSpigotAt> {
            inputJar.set(remapVanillaJarSpigot.flatMap { it.outputJar })
            mapping.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
            spigotAt.set(extension.craftBukkit.atFile)
        }

        val remapSpigotSources by tasks.registering<RemapSources> {
            spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
            spigotApiDir.set(patchSpigotApi.flatMap { it.outputDir })
            mappings.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
            vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
            vanillaRemappedSpigotJar.set(removeSpigotExcludes.flatMap { it.outputZip })
            spigotApiDeps.set(downloadSpigotDependencies.flatMap { it.apiOutputDir })
            spigotServerDeps.set(downloadSpigotDependencies.flatMap { it.serverOutputDir })
            constructors.set(initialTasks.extractMcp.flatMap { it.constructors })
        }

        val remapGeneratedAt by tasks.registering<RemapAccessTransform> {
            inputFile.set(remapSpigotSources.flatMap { it.generatedAt })
            mappings.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
        }

        val mergeGeneratedAts by tasks.registering<MergeAccessTransforms> {
            inputFiles.add(remapGeneratedAt.flatMap { it.outputFile })
            inputFiles.add(remapSpigotAt.flatMap { it.outputFile })
        }

        return SpigotTasks(
            generateSpigotSrgs,
            decompileVanillaJarSpigot,
            patchSpigotApi,
            patchSpigotServer,
            remapSpigotSources,
            mergeGeneratedAts
        )
    }

    private fun Project.createPatchRemapTasks(
        initialTasks: InitialTasks,
        generalTasks: GeneralTasks,
        mcpTasks: McpTasks,
        spigotTasks: SpigotTasks
    ) {
        val extension: PaperweightExtension = ext

        /*
         * I don't remember what this is supposed to be for tbh
        val patchPaperServerForPatchRemap by tasks.registering<ApplyPaperPatches> {
            patchDir.set(extension.paper.spigotServerPatchDir)
            remappedSource.set(spigotTasks.remapSpigotSources.flatMap { it.outputZip })

            outputDir.set(cache.resolve("patch-paper-server-for-remap"))
        }
         */

        val applyVanillaSrgAt by tasks.registering<ApplyAccessTransform> {
            inputJar.set(mcpTasks.remapVanillaJarSrg.flatMap { it.outputJar })
            atFile.set(spigotTasks.mergeGeneratedAts.flatMap { it.outputFile })
        }

        val remapPatches by tasks.registering<RemapPatches> {
            inputPatchDir.set(extension.paper.unmappedSpigotServerPatchDir)
            sourceJar.set(spigotTasks.remapSpigotSources.flatMap { it.outputZip })
            apiPatchDir.set(extension.paper.spigotApiPatchDir)

            mappingsFile.set(spigotTasks.generateSpigotSrgs.flatMap { it.spigotToSrg })

            // Pull in as many jars as possible to reduce the possibility of type bindings not resolving
            classpathJars.add(generalTasks.downloadServerJar.flatMap { it.outputJar })
            classpathJars.add(spigotTasks.remapSpigotSources.flatMap { it.vanillaRemappedSpigotJar })
            classpathJars.add(applyVanillaSrgAt.flatMap { it.outputJar })

            spigotApiDir.set(spigotTasks.patchSpigotApi.flatMap { it.outputDir })
            spigotServerDir.set(spigotTasks.patchSpigotServer.flatMap { it.outputDir })
            spigotDecompJar.set(spigotTasks.decompileVanillaJarSpigot.flatMap { it.outputJar })
            constructors.set(initialTasks.extractMcp.flatMap { it.constructors })

            parameterNames.set(spigotTasks.remapSpigotSources.flatMap { it.parameterNames })

            outputPatchDir.set(extension.paper.remappedSpigotServerPatchDir)
        }
    }
}
