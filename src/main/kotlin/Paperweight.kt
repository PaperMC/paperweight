/*
 * Copyright 2018 Kyle Wood
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperweight

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.CONFIG_MAPPINGS
import io.papermc.paperweight.util.CONFIG_MCP_DATA
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.ExtractConfigTask
import io.papermc.paperweight.util.ExtractMcpMappingsTask
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.RemapSrgSources
import io.papermc.paperweight.util.RunForgeFlower
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.createBasics
import io.papermc.paperweight.util.ext
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.register
import java.io.File

class Paperweight : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightExtension::class.java)

        Git(target.projectDir)("submodule", "update", "--init").execute()

        val extension = target.ext

        val getRemoteJsons = target.tasks.register<GetRemoteJsons>("getRemoteJsons")
        val validateConfig = target.tasks.register<ValidateConfig>("validateConfig") {
            minecraftVersion.set(extension.minecraftVersion)
            mcpVersion.set(extension.mcpVersionProvider)
            mcpJson.set(getRemoteJsons.flatMap { it.mcpJson })
        }
        val gatherBuildData = target.tasks.register<GatherBuildData>("gatherBuildData") {
            buildDataInfoFile.set(extension.craftBukkit.buildDataInfo)
        }

        createConfigurations(target, getRemoteJsons)
        createBasics(target)
        setupConfigurations(target, validateConfig)

        createTasks(target, gatherBuildData)
    }

    private fun createConfigurations(project: Project, getRemoteJsons: TaskProvider<GetRemoteJsons>) {
        // For remapSources, we need to pull down a sonatype & spigot dep
        project.configurations.create(Constants.SPIGOT_DEP_CONFIG)
        project.repositories.maven("https://oss.sonatype.org/content/repositories/snapshots/")
        project.repositories.maven("https://hub.spigotmc.org/nexus/content/groups/public/")

        val config = project.configurations.create("minecraft")

        // These dependencies will be resolved using the `minecraft` repo
        config.resolutionStrategy.eachDependency {
            val group = requested.group
            val artifact = requested.name

            val obj = getRemoteJsons.flatMap { it.versionJson }.get().get()["libraries"].array.firstOrNull {
                it["name"].string.startsWith("$group:$artifact")
            } ?: return@eachDependency

            val name = obj["name"].string
            val index = name.lastIndexOf(':')
            if (index == -1) {
                // Shouldn't be possible, but just a guard
                return@eachDependency
            }

            useVersion(name.substring(index + 1))
            because("Match MC vanilla version")
        }
    }

    private fun createTasks(project: Project, gatherBuildData: TaskProvider<GatherBuildData>) {
        val cache = project.cache
        val extension = project.ext
        val buildDataInfo = gatherBuildData.flatMap { it.buildDataInfo }

        val extractMcpData = project.tasks.register<ExtractMcpDataTask>("extractMcpData") {
            config.set(CONFIG_MCP_DATA)

            destinationDir.set(cache.resolve(Constants.mcpDataDir(extension)))
        }

        val extractMcpMappings = project.tasks.register<ExtractMcpMappingsTask>("extractMcpMappings") {
            config.set(CONFIG_MAPPINGS)

            destinationDir.set(cache.resolve(Constants.mcpMappingDir(extension)))
        }

        val mcpRewrites = project.tasks.register<PatchMcpCsv>("mcpRewrites") {
            fieldsCsv.set(extractMcpMappings.flatMap { it.fieldsCsv })
            methodsCsv.set(extractMcpMappings.flatMap { it.methodsCsv })
            paramsCsv.set(extractMcpMappings.flatMap { it.paramsCsv })
            changesFile.set(extension.paper.mcpRewritesFile)

            paperFieldCsv.set(cache.resolve(Constants.paperMcpFieldsCsv(extension)))
            paperMethodCsv.set(cache.resolve(Constants.paperMcpMethodsCsv(extension)))
            paperParamCsv.set(cache.resolve(Constants.paperMcpParamsCsv(extension)))
        }

        val generateSrgs = project.tasks.register<GenerateSrgs>("generateSrgs") {
            methodsCsv.set(mcpRewrites.flatMap { it.paperMethodCsv })
            fieldsCsv.set(mcpRewrites.flatMap { it.paperFieldCsv })
            inSrg.set(extractMcpData.flatMap { it.joinedSrg })

            notchToSrg.set(cache.resolve(Constants.notchToSrg(extension)))
            notchToMcp.set(cache.resolve(Constants.notchToMcp(extension)))
            srgToNotch.set(cache.resolve(Constants.srgToNotch(extension)))
            srgToMcp.set(cache.resolve(Constants.srgToMcp(extension)))
            mcpToNotch.set(cache.resolve(Constants.mcpToNotch(extension)))
            mcpToSrg.set(cache.resolve(Constants.mcpToSrg(extension)))
        }

        val generateSpigotSrgs = project.tasks.register<GenerateSpigotSrgs>("generateSpigotSrgs") {
            notchToSrg.set(generateSrgs.flatMap { it.notchToSrg })
            srgToMcp.set(generateSrgs.flatMap { it.srgToMcp })
            classMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))

            spigotToSrg.set(cache.resolve(Constants.spigotToSrg(extension)))
            spigotToMcp.set(cache.resolve(Constants.spigotToMcp(extension)))
            spigotToNotch.set(cache.resolve(Constants.spigotToNotch(extension)))
            srgToSpigot.set(cache.resolve(Constants.srgToSpigot(extension)))
            mcpToSpigot.set(cache.resolve(Constants.mcpToSpigot(extension)))
            notchToSpigot.set(cache.resolve(Constants.notchToSpigot(extension)))
        }

        val downloadServerJar = project.tasks.register<DownloadServerJar>("downloadServerJar") {
            downloadUrl.set(buildDataInfo.map { it.serverUrl })
            hash.set(buildDataInfo.map { it.minecraftHash })

            outputJar.set(cache.resolve(Constants.paperJarFile(extension, "vanilla")))
        }

        val filterVanillaJar = project.tasks.register<Zip>("filterVanillaJar") {
            archiveFileName.set("filtered.jar")
            destinationDirectory.set(cache.resolve(Constants.paperCache(extension)))

            from(project.zipTree(downloadServerJar.flatMap { it.outputJar })) {
                include("/*.class")
                include("/net/minecraft/**")
            }
        }

        val remapVanillaJar = project.tasks.register<RemapVanillaJarSpigot>("remapVanillaJar") {
            inputJar.set(project.layout.file(filterVanillaJar.map { it.outputs.files.singleFile }))
            specialSourceJar.set(extension.craftBukkit.specialSourceJar)
            specialSource2Jar.set(extension.craftBukkit.specialSource2Jar)
            classMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))
            accessTransformers.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.accessTransforms }))

            outputJar.set(cache.resolve(Constants.paperJarFile(extension, "spigot")))
        }

        val decompileVanillaJarSpigot = project.tasks.register<DecompileVanillaJar>("decompileVanillaJarSpigot") {
            inputJar.set(remapVanillaJar.flatMap { it.outputJar })
            fernFlowerJar.set(extension.craftBukkit.fernFlowerJar)

            outputJar.set(cache.resolve(Constants.paperJarFile(extension, "spigot-decomp")))
        }

        // TODO move this to a zip task
        val patchCraftBukkit = project.tasks.register<ApplyDiffPatches>("patchCraftBukkit") {
            sourceJar.set(decompileVanillaJarSpigot.flatMap { it.outputJar })
            branch.set("patched")
            patchDir.set(extension.craftBukkit.patchDir)
            basePatchDir.set(extension.craftBukkit.sourceDir)
            sourceBasePath.set("net/minecraft/server")

            baseDir.set(extension.craftBukkit.craftBukkitDir)
        }

        val cloneSpigotApi = project.tasks.register<CloneRepo>("cloneSpigotApi") {
            repo.set(extension.craftBukkit.bukkitDir)
            branch.set("HEAD")
            sourceName.set("Bukkit")
            targetName.set("Spigot-API")
        }

        val patchSpigotApi = project.tasks.register<ApplyGitPatches>("patchSpigotApi") {
            inputZip.set(cloneSpigotApi.flatMap { it.outputZip })
            targetName.set(cloneSpigotApi.flatMap { it.targetName })
            patchDir.set(extension.spigot.bukkitPatchDir)
        }

        val cloneSpigotServer = project.tasks.register<CloneRepo>("cloneSpigotServer") {
            repo.set(patchCraftBukkit.flatMap { it.baseDir })
            branch.set(patchCraftBukkit.flatMap { it.branch })
            sourceName.set("CraftBukkit")
            targetName.set("Spigot-Server")
        }

        val patchSpigotServer = project.tasks.register<ApplyGitPatches>("patchSpigotServer") {
            inputZip.set(cloneSpigotServer.flatMap { it.outputZip })
            targetName.set(cloneSpigotServer.flatMap { it.targetName })
            patchDir.set(extension.spigot.craftBukkitPatchDir)
        }

        val patchSpigot = project.tasks.register("patchSpigot") {
            dependsOn(patchSpigotApi, patchSpigotServer)
        }

        val setupSpigotDependencyConfig =
            project.tasks.register<SetupSpigotDependencyConfig>("setupSpigotDependencyConfig") {
                dependsOn(patchSpigot)
                spigotApiZip.set(patchSpigotApi.flatMap { it.outputZip })
                spigotServerZip.set(patchSpigotServer.flatMap { it.outputZip})
                configurationName.set(Constants.SPIGOT_DEP_CONFIG)
            }

        val remapVanillaJarSrg = project.tasks.register<RemapVanillaJarSrg>("remapVanillaJarSrg") {
            inputJar.set(project.layout.file(filterVanillaJar.map { it.outputs.files.singleFile }))
            access.set(extractMcpData.flatMap { it.destinationDir.file("config/access.txt") })
            constructors.set(extractMcpData.flatMap { it.destinationDir.file("config/constructors.txt") })
            exceptions.set(extractMcpData.flatMap { it.destinationDir.file("config/exceptions.txt") })
            mappings.set(generateSrgs.flatMap { it.notchToSrg })

            outputJar.set(cache.resolve(Constants.paperJarFile(extension, "srg")))
        }

        val remapSpigotSources = project.tasks.register<RemapSources>("remapSpigotSources") {
            inputZip.set(patchSpigotServer.flatMap { it.outputZip })
            constructors.set(extractMcpData.flatMap { it.destinationDir.file("config/constructors.txt") })
            spigotToSrg.set(generateSpigotSrgs.flatMap { it.spigotToSrg })
            vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
            vanillaRemappedSpigotJar.set(remapVanillaJar.flatMap { it.outputJar })
            vanillaRemappedSrgJar.set(remapVanillaJarSrg.flatMap { it.outputJar })
            spigotApiZip.set(patchSpigotApi.flatMap { it.outputZip })
            config.set(setupSpigotDependencyConfig.flatMap { it.configurationName })

            generatedAt.set(project.file(Constants.taskOutput(project, "spigot_at.cfg")))
        }

        val remapSrgSourcesSpigot = project.tasks.register<RemapSrgSources>("remapSrgSourcesSpigot") {
            inputZip.set(remapSpigotSources.flatMap { it.outputZip })
            methodsCsv.set(mcpRewrites.flatMap { it.methodsCsv })
            fieldsCsv.set(mcpRewrites.flatMap { it.fieldsCsv })
            paramsCsv.set(mcpRewrites.flatMap { it.paramsCsv })
        }

        val decompileVanillaJarForge = project.tasks.register<RunForgeFlower>("decompileVanillaJarForge") {
            inputJar.set(remapVanillaJarSrg.flatMap { it.outputJar })

            outputJar.set(cache.resolve(Constants.paperJarFile(extension, "srg-decomp")))
        }

        val applyMcpPatches = project.tasks.register<ApplyMcpPatches>("applyMcpPatches") {
            inputJar.set(decompileVanillaJarForge.flatMap { it.outputJar })
            serverPatchDir.set(extractMcpData.flatMap { it.patchesDir })

            outputJar.set(cache.resolve(Constants.paperJarFile(extension, "srg-decomp-patched")))
        }

        val remapSrgSourcesSpigotVanilla = project.tasks.register<RemapSrgSources>("remapSrgSourcesSpigotVanilla") {
            inputZip.set(applyMcpPatches.flatMap { it.outputJar })
            methodsCsv.set(mcpRewrites.flatMap { it.methodsCsv })
            fieldsCsv.set(mcpRewrites.flatMap { it.fieldsCsv })
            paramsCsv.set(mcpRewrites.flatMap { it.paramsCsv })

            outputZip.set(cache.resolve(Constants.paperJarFile(extension, "mcp-decomp")))
        }
    }
}
