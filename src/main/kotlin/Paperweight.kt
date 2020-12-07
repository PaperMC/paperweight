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
import io.papermc.paperweight.tasks.AddAdditionalSpigotMappings
import io.papermc.paperweight.tasks.ApplyDiffPatches
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.ApplyPaperPatches
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
import io.papermc.paperweight.tasks.FixJar
import io.papermc.paperweight.tasks.GenerateSpigotMappings
import io.papermc.paperweight.tasks.GenerateMappings
import io.papermc.paperweight.tasks.InspectVanillaJar
import io.papermc.paperweight.tasks.MergeAccessTransforms
import io.papermc.paperweight.tasks.PatchMappings
import io.papermc.paperweight.tasks.RemapAccessTransform
import io.papermc.paperweight.tasks.RemapJar
import io.papermc.paperweight.tasks.RemapSpigotAt
import io.papermc.paperweight.tasks.RemapVanillaJarSpigot
import io.papermc.paperweight.tasks.RunForgeFlower
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
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.registerIfAbsent

class Paperweight : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightExtension::class.java, target.objects, target.layout)

        val downloadService = target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "Paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        // Make sure the submodules are initialized
        Git(target.projectDir)("submodule", "update", "--init").execute()

        target.configurations.create(Constants.YARN_CONFIG)
        target.configurations.create(Constants.REMAPPER_CONFIG)

        target.repositories.apply {
            mavenCentral()
            // Both of these are needed for Spigot
            maven("https://oss.sonatype.org/content/repositories/snapshots/")
            maven("https://hub.spigotmc.org/nexus/content/groups/public/")

            maven("https://maven.fabricmc.net/") {
                content {
                    onlyForConfigurations(Constants.YARN_CONFIG, Constants.REMAPPER_CONFIG)
                }
            }
        }

        target.createTasks(downloadService)
    }

    private fun Project.createTasks(downloadService: Provider<DownloadService>) {
        val extension = ext

        val initialTasks = createInitialTasks(downloadService)
        val generalTasks = createGeneralTasks(downloadService)
        val mcpTasks = createMcpTasks(downloadService, initialTasks, generalTasks)
        val spigotTasks = createSpigotTasks(downloadService, initialTasks, generalTasks, mcpTasks)

        createPatchRemapTasks(initialTasks, generalTasks, mcpTasks, spigotTasks)

        val applyMergedAt by tasks.registering<ApplyAccessTransform> {
            inputJar.set(mcpTasks.fixJar.flatMap { it.outputJar })
            atFile.set(spigotTasks.mergeGeneratedAts.flatMap { it.outputFile })
        }

        val writeLibrariesFile by tasks.registering<WriteLibrariesFile> {
            libraries.set(mcpTasks.downloadMcLibraries.flatMap { it.outputDir })
        }

        val decompileVanillaJarYarn by tasks.registering<RunForgeFlower> {
            executable.set(initialTasks.downloadMcpTools.flatMap { it.forgeFlowerFile })
            configFile.set(initialTasks.extractMcp.flatMap { it.configFile })

            inputJar.set(applyMergedAt.flatMap { it.outputJar })
            libraries.set(writeLibrariesFile.flatMap { it.outputFile })
        }

//        val mergeRemappedSources by tasks.registering<Merge> {
//            inputJars.add(spigotTasks.remapSpigotSources.flatMap { it.outputZip })
//            inputJars.add(applySourceAt.flatMap { it.outputZip })
//        }

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
            remappedSource.set(spigotTasks.remapSpigotSources.flatMap { it.outputZip })
            templateGitIgnore.set(layout.projectDirectory.file(".gitignore"))
            sourceMcDevJar.set(decompileVanillaJarYarn.flatMap { it.outputJar })
            mcLibrariesDir.set(mcpTasks.downloadMcLibraries.flatMap { it.outputDir }.get())
            libraryImports.set(extension.paper.libraryClassImports)

            outputDir.set(extension.paper.paperServerDir)
        }

        val patchPaper by tasks.registering<Task> {
            group = "Paper"
            description = "Set up the Paper development environment"
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
        val downloadMappings: TaskProvider<DownloadTask>,
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
        val generateMappings: TaskProvider<GenerateMappings>,
        val fixJar: TaskProvider<FixJar>,
        val downloadMcLibraries: TaskProvider<DownloadMcLibraries>
    )

    data class SpigotTasks(
        val patchMappings: TaskProvider<PatchMappings>,
        val decompileVanillaJarSpigot: TaskProvider<DecompileVanillaJar>,
        val patchSpigotApi: TaskProvider<ApplyGitPatches>,
        val patchSpigotServer: TaskProvider<ApplyGitPatches>,
        val remapSpigotSources: TaskProvider<RemapSources>,
        val mergeGeneratedAts: TaskProvider<MergeAccessTransforms>
    )

    private fun Project.createInitialTasks(downloadService: Provider<DownloadService>): InitialTasks {
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext

        val downloadMcManifest by tasks.registering<DownloadTask> {
            url.set(Constants.MC_MANIFEST_URL)
            outputFile.set(cache.resolve(Constants.MC_MANIFEST))

            downloader.set(downloadService)
        }

        val mcManifest = downloadMcManifest.flatMap { it.outputFile }.map { gson.fromJson<MinecraftManifest>(it) }

        val downloadMcVersionManifest by tasks.registering<DownloadTask> {
            url.set(mcManifest.zip(extension.minecraftVersion) { manifest, version ->
                manifest.versions.first { it.id == version }.url
            })
            outputFile.set(cache.resolve(Constants.VERSION_JSON))

            downloader.set(downloadService)
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

        val downloadMappings by tasks.registering<DownloadTask> {
            url.set(versionManifest.map { version ->
                version["downloads"]["server_mappings"]["url"].string
            })
            outputFile.set(cache.resolve(Constants.SERVER_MAPPINGS))

            downloader.set(downloadService)
        }

        val downloadMcpFiles by tasks.registering<DownloadMcpFiles> {
            mcpMinecraftVersion.set(extension.mcpMinecraftVersion)
            mcpConfigVersion.set(extension.mcpConfigVersion)
            mcpMappingsChannel.set(extension.mcpMappingsChannel)
            mcpMappingsVersion.set(extension.mcpMappingsVersion)

            configZip.set(cache.resolve(Constants.MCP_ZIPS_PATH).resolve("McpConfig.zip"))
            mappingsZip.set(cache.resolve(Constants.MCP_ZIPS_PATH).resolve("McpMappings.zip"))

            downloader.set(downloadService)
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

            downloader.set(downloadService)
        }

        return InitialTasks(
            setupMcLibraries,
            downloadMappings,
            extractMcpConfig,
            extractMcpMappings,
            downloadMcpTools
        )
    }

    private fun Project.createGeneralTasks(downloadService: Provider<DownloadService>): GeneralTasks {
        val buildDataInfo: Provider<BuildDataInfo> = contents(ext.craftBukkit.buildDataInfo) {
            gson.fromJson(it)
        }

        val downloadServerJar by tasks.registering<DownloadServerJar> {
            downloadUrl.set(buildDataInfo.map { it.serverUrl })
            hash.set(buildDataInfo.map { it.minecraftHash })

            downloader.set(downloadService)
        }

        val filterVanillaJar by tasks.registering<Filter> {
            inputJar.set(downloadServerJar.flatMap { it.outputJar })
            includes.set(listOf("/*.class", "/net/minecraft/**"))
        }

        return GeneralTasks(buildDataInfo, downloadServerJar, filterVanillaJar)
    }

    private fun Project.createMcpTasks(
        downloadService: Provider<DownloadService>,
        initialTasks: InitialTasks,
        generalTasks: GeneralTasks
    ): McpTasks {
        val filterVanillaJar: TaskProvider<Filter> = generalTasks.filterVanillaJar
        val cache: File = layout.cache

//        val mcpRewrites by tasks.registering<PatchMcpCsv> {
//            fieldsCsv.set(initialTasks.mcpMappings.flatMap { it.fieldsCsv })
//            methodsCsv.set(initialTasks.mcpMappings.flatMap { it.methodsCsv })
//            paramsCsv.set(initialTasks.mcpMappings.flatMap { it.paramsCsv })
//            changesFile.set(extension.paper.mcpRewritesFile)
//
//            paperFieldCsv.set(cache.resolve(Constants.PAPER_FIELDS_CSV))
//            paperMethodCsv.set(cache.resolve(Constants.PAPER_METHODS_CSV))
//            paperParamCsv.set(cache.resolve(Constants.PAPER_PARAMS_CSV))
//        }

        val generateMappings by tasks.registering<GenerateMappings> {
            vanillaJar.set(generalTasks.filterVanillaJar.flatMap { it.outputJar })

            vanillaMappings.set(initialTasks.downloadMappings.flatMap { it.outputFile })
            yarnMappings.fileProvider(configurations.named(Constants.YARN_CONFIG).map { it.singleFile })

            outputMappings.set(cache.resolve(Constants.SRG_DIR).resolve("merged.tiny"))
        }

        val remapJar by tasks.registering<RemapJar> {
            inputJar.set(filterVanillaJar.flatMap { it.outputJar })
            mappingsFile.set(generateMappings.flatMap { it.outputMappings })
            remapper.fileProvider(configurations.named(Constants.REMAPPER_CONFIG).map { it.singleFile })
        }

        val fixJar by tasks.registering<FixJar> {
//            executable.set(initialTasks.downloadMcpTools.flatMap { it.mcInjectorFile })
            inputJar.set(remapJar.flatMap { it.outputJar })
        }

//        val remapVanillaJarSrg by tasks.registering<RunSpecialSource> {
//            inputJar.set(filterVanillaJar.flatMap { it.outputJar })
//            mappings.set(generateSrgs.flatMap { it.notchToSrg })
//
//            executable.set(initialTasks.downloadMcpTools.flatMap { it.specialSourceFile })
//            configFile.set(initialTasks.extractMcp.flatMap { it.configFile })
//        }
//
//        val injectVanillaJarSrg by tasks.registering<RunMcInjector> {
//            executable.set(initialTasks.downloadMcpTools.flatMap { it.mcInjectorFile })
//            configFile.set(initialTasks.extractMcp.flatMap { it.configFile })
//
//            exceptions.set(initialTasks.extractMcp.flatMap { it.exceptions })
//            access.set(initialTasks.extractMcp.flatMap { it.access })
//            constructors.set(initialTasks.extractMcp.flatMap { it.constructors })
//
//            inputJar.set(remapVanillaJarSrg.flatMap { it.outputJar })
//        }

        val downloadMcLibraries by tasks.registering<DownloadMcLibraries> {
            mcLibrariesFile.set(initialTasks.setupMcLibraries.flatMap { it.outputFile })
            mcRepo.set(Constants.MC_LIBRARY_URL)
            outputDir.set(cache.resolve(Constants.MINECRAFT_JARS_PATH))

            downloader.set(downloadService)
        }
//
//        val applyMcpPatches by tasks.registering<ApplyMcpPatches> {
//            inputZip.set(decompileVanillaJarSrg.flatMap { it.outputJar })
//            serverPatchDir.set(initialTasks.extractMcp.flatMap { it.patchDir })
//            configFile.set(cache.resolve(Constants.MCP_CONFIG_JSON))
//        }

        return McpTasks(generateMappings, fixJar, downloadMcLibraries)
    }

    private fun Project.createSpigotTasks(
        downloadService: Provider<DownloadService>,
        initialTasks: InitialTasks,
        generalTasks: GeneralTasks,
        mcpTasks: McpTasks
    ): SpigotTasks {
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext

        val (buildDataInfo, downloadServerJar, filterVanillaJar) = generalTasks
        val (generateMappings, _, _) = mcpTasks

        val addAdditionalSpigotMappings by tasks.registering<AddAdditionalSpigotMappings> {
            classSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            additionalClassEntriesSrg.set(extension.paper.additionalSpigotClassMappings)
            additionalMemberEntriesSrg.set(extension.paper.additionalSpigotMemberMappings)
        }

        val inspectVanillaJar by tasks.registering<InspectVanillaJar> {
            inputJar.set(downloadServerJar.flatMap { it.outputJar })
        }

        val generateSpigotMappings by tasks.registering<GenerateSpigotMappings> {
            classMappings.set(addAdditionalSpigotMappings.flatMap { it.outputClassSrg })
            memberMappings.set(addAdditionalSpigotMappings.flatMap { it.outputMemberSrg })
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))

            loggerFields.set(inspectVanillaJar.flatMap { it.loggerFile })
            paramIndexes.set(inspectVanillaJar.flatMap { it.paramIndexes })
            syntheticMethods.set(inspectVanillaJar.flatMap { it.syntheticMethods })

            sourceMappings.set(generateMappings.flatMap { it.outputMappings })

            outputMappings.set(cache.resolve(Constants.SRG_DIR).resolve("spigot-named.tiny"))
        }

        val patchMappings by tasks.registering<PatchMappings> {
            inputMappings.set(generateSpigotMappings.flatMap { it.outputMappings })
            patchMappings.set(extension.paper.mappingsPatch)

            outputMappings.set(cache.resolve(Constants.SRG_DIR).resolve("spigot-named-patched.tiny"))
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
            outputDir.set(cache.resolve(Constants.SPIGOT_JARS_PATH))

            downloader.set(downloadService)
        }

        val remapSpigotAt by tasks.registering<RemapSpigotAt> {
            inputJar.set(remapVanillaJarSpigot.flatMap { it.outputJar })
            mapping.set(patchMappings.flatMap { it.outputMappings })
            spigotAt.set(extension.craftBukkit.atFile)
        }

        val remapSpigotSources by tasks.registering<RemapSources> {
            spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
            spigotApiDir.set(patchSpigotApi.flatMap { it.outputDir })
            mappings.set(patchMappings.flatMap { it.outputMappings })
            vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
            vanillaRemappedSpigotJar.set(removeSpigotExcludes.flatMap { it.outputZip })
            spigotDeps.set(downloadSpigotDependencies.flatMap { it.outputDir })
            constructors.set(initialTasks.extractMcp.flatMap { it.constructors })
        }

        val remapGeneratedAt by tasks.registering<RemapAccessTransform> {
            inputFile.set(remapSpigotSources.flatMap { it.generatedAt })
            mappings.set(patchMappings.flatMap { it.outputMappings })
        }

        val mergeGeneratedAts by tasks.registering<MergeAccessTransforms> {
            inputFiles.add(remapGeneratedAt.flatMap { it.outputFile })
            inputFiles.add(remapSpigotAt.flatMap { it.outputFile })
        }

        return SpigotTasks(
            patchMappings,
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
            inputJar.set(mcpTasks.fixJar.flatMap { it.outputJar })
            atFile.set(spigotTasks.mergeGeneratedAts.flatMap { it.outputFile })
        }

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
        val remapPatches by tasks.registering<RemapPatches> {
            group = "Paper"
            description = "EXPERIMENTAL & BROKEN: Attempt to remap Paper's patches from Spigot mappings to SRG."

            inputPatchDir.set(extension.paper.unmappedSpigotServerPatchDir)
//            sourceJar.set(spigotTasks.remapSpigotSources.flatMap { it.outputZip }.get())
            apiPatchDir.set(extension.paper.spigotApiPatchDir)

            mappingsFile.set(spigotTasks.patchMappings.flatMap { it.outputMappings }.get())

            // Pull in as many jars as possible to reduce the possibility of type bindings not resolving
            classpathJars.add(generalTasks.downloadServerJar.flatMap { it.outputJar }.get())
            classpathJars.add(spigotTasks.remapSpigotSources.flatMap { it.vanillaRemappedSpigotJar }.get())
            classpathJars.add(applyVanillaSrgAt.flatMap { it.outputJar }.get())

            spigotApiDir.set(spigotTasks.patchSpigotApi.flatMap { it.outputDir }.get())
            spigotServerDir.set(spigotTasks.patchSpigotServer.flatMap { it.outputDir }.get())
            spigotDecompJar.set(spigotTasks.decompileVanillaJarSpigot.flatMap { it.outputJar }.get())
            constructors.set(initialTasks.extractMcp.flatMap { it.constructors }.get())

            // library class imports
            mcLibrariesDir.set(mcpTasks.downloadMcLibraries.flatMap { it.outputDir }.get())
            libraryImports.set(extension.paper.libraryClassImports)

            parameterNames.set(spigotTasks.remapSpigotSources.flatMap { it.parameterNames }.get())

            outputPatchDir.set(extension.paper.remappedSpigotServerPatchDir)
        }
    }
}
