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
import io.papermc.paperweight.tasks.ApplyDiffPatches
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.ApplyMcpPatches
import io.papermc.paperweight.tasks.CloneRepo
import io.papermc.paperweight.tasks.DecompileVanillaJar
import io.papermc.paperweight.tasks.DownloadServerJar
import io.papermc.paperweight.tasks.GenerateSpigotSrgs
import io.papermc.paperweight.tasks.GenerateSrgs
import io.papermc.paperweight.tasks.PatchMcpCsv
import io.papermc.paperweight.tasks.RemapSources
import io.papermc.paperweight.tasks.RemapVanillaJarSpigot
import io.papermc.paperweight.tasks.RemapVanillaJarSrg
import io.papermc.paperweight.tasks.SetupSpigotDependencyConfig
import io.papermc.paperweight.util.CONFIG_MAPPINGS
import io.papermc.paperweight.util.CONFIG_MCP_DATA
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.ExtractConfigTask
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.RemapSrgSources
import io.papermc.paperweight.util.RunForgeFlower
import io.papermc.paperweight.util.TASK_EXTRACT_MAPPINGS
import io.papermc.paperweight.util.TASK_EXTRACT_MCP
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.createBasics
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.getRemoteJsons
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.invoke
import io.papermc.paperweight.util.register
import io.papermc.paperweight.util.setupConfigurations
import io.papermc.paperweight.util.validateConfig
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.maven
import util.BuildDataInfo

class Paperweight : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightExtension::class.java)

        Git(target.projectDir)("submodule", "update", "--init").execute()

        createConfigurations(target)

        createBasics(target)

        // Wait before creating tasks
        target.afterEvaluate {
            // Order is very important!
            getRemoteJsons(this)
            validateConfig(this)
            setupConfigurations(this)

            gatherBuildData(this)

            createTasks(this)
        }
    }

    private fun createConfigurations(project: Project) {
        // For remapSources, we need to pull down a sonatype & spigot dep
        project.configurations.create(Constants.SPIGOT_DEP_CONFIG)
        project.repositories.maven("https://oss.sonatype.org/content/repositories/snapshots/")
        project.repositories.maven("https://hub.spigotmc.org/nexus/content/groups/public/")

        val config = project.configurations.create("minecraft")

        // These dependencies will be resolved using the `minecraft` repo
        config.resolutionStrategy.eachDependency {
            val group = requested.group
            val artifact = requested.name

            val obj = project.ext.versionJson["libraries"].array.firstOrNull {
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

    private fun gatherBuildData(project: Project) {
        val extension = project.ext

        try {
            project.file(extension.craftBukkit.buildDataInfo).bufferedReader().use {
                extension.buildDataInfo = gson.fromJson(it, BuildDataInfo::class.java)
            }
        } catch (e: Exception) {
            throw PaperweightException("Failed to read build info file", e)
        }
    }

    private fun createTasks(project: Project) = project.run {
        val cache = this.cache
        val extension = this.ext

        val extractMcpData = tasks.register<ExtractConfigTask>(TASK_EXTRACT_MCP) {
            config = CONFIG_MCP_DATA
            destinationDir = cache.resolve(Constants.mcpDataDir(extension))
        }

        val extractMcpMappings = tasks.register<ExtractConfigTask>(TASK_EXTRACT_MAPPINGS) {
            config = CONFIG_MAPPINGS
            destinationDir = cache.resolve(Constants.mcpMappingDir(extension))
        }

        val mcpRewrites = tasks.register<PatchMcpCsv>(Constants.MCP_REWRITES) {
            dependsOn(extractMcpData, extractMcpMappings)

            fieldsCsv = cache.resolve(Constants.mcpFieldsCsv(extension))
            methodsCsv = cache.resolve(Constants.mcpMethodsCsv(extension))
            paramsCsv = cache.resolve(Constants.mcpParamsCsv(extension))
            changesFile = cache.resolve(project.file(extension.paper.mcpRewritesFile))

            paperFieldCsv = cache.resolve(Constants.paperMcpFieldsCsv(extension))
            paperMethodCsv = cache.resolve(Constants.paperMcpMethodsCsv(extension))
            paperParamCsv = cache.resolve(Constants.paperMcpParamsCsv(extension))
        }

        val generateSrgs = tasks.register<GenerateSrgs>(Constants.GENERATE_SRGS) {
            dependsOn(mcpRewrites)

            mcpRewrites {
                methodsCsv = it.paperMethodCsv
                fieldsCsv = it.paperFieldCsv
            }

            inSrg = cache.resolve("${Constants.mcpDataDir(extension)}/config/joined.tsrg")

            notchToSrg = cache.resolve(Constants.notchToSrg(extension))
            notchToMcp = cache.resolve(Constants.notchToMcp(extension))
            srgToNotch = cache.resolve(Constants.srgToNotch(extension))
            srgToMcp = cache.resolve(Constants.srgToMcp(extension))
            mcpToNotch = cache.resolve(Constants.mcpToNotch(extension))
            mcpToSrg = cache.resolve(Constants.mcpToSrg(extension))
        }

        val generateSpigotSrgs = tasks.register<GenerateSpigotSrgs>(Constants.GENERATE_SPIGOT_SRGS) {
            dependsOn(generateSrgs)

            generateSrgs {
                notchToSrg = it.notchToSrg
                srgToMcp = it.srgToMcp
            }

            classMappings = project.file(Constants.spigotClassMappings(extension))
            memberMappings = project.file(Constants.spigotMemberMappings(extension))
            packageMappings = project.file(Constants.spigotPackageMappings(extension))

            spigotToSrg = cache.resolve(Constants.spigotToSrg(extension))
            spigotToMcp = cache.resolve(Constants.spigotToMcp(extension))
            spigotToNotch = cache.resolve(Constants.spigotToNotch(extension))
            srgToSpigot = cache.resolve(Constants.srgToSpigot(extension))
            mcpToSpigot = cache.resolve(Constants.mcpToSpigot(extension))
            notchToSpigot = cache.resolve(Constants.notchToSpigot(extension))
        }

        val downloadServerJar = tasks.register<DownloadServerJar>(Constants.DOWNLOAD_SERVER_JAR) {
            downloadUrl = extension.buildDataInfo.serverUrl
            hash = extension.buildDataInfo.minecraftHash

            outputJar = cache.resolve(Constants.paperJarFile(extension, "vanilla"))
        }

        val filterVanillaJar = tasks.register<Zip>(Constants.FILTER_VANILLA_JAR) {
            dependsOn(downloadServerJar)

            archiveName = "filtered.jar"
            destinationDir = cache.resolve(Constants.paperCache(extension))

            downloadServerJar {
                from(zipTree(it.outputJar)) {
                    include("/*.class")
                    include("/net/minecraft/**")
                }
            }
        }

        val remapVanillaJar = tasks.register<RemapVanillaJarSpigot>(Constants.REMAP_VANILLA_JAR) {
            dependsOn(filterVanillaJar)

            filterVanillaJar {
                inputJar = it.outputs.files.singleFile
            }

            specialSourceJar = extension.craftBukkit.specialSourceJar
            specialSource2Jar = extension.craftBukkit.specialSource2Jar

            val info = extension.buildDataInfo
            val mappingsDir = project.file(extension.craftBukkit.mappingsDir)
            classMappings = mappingsDir.resolve(info.classMappings)
            memberMappings = mappingsDir.resolve(info.memberMappings)
            packageMappings = mappingsDir.resolve(info.packageMappings)
            accessTransformers = mappingsDir.resolve(info.accessTransforms)

            outputJar = cache.resolve(Constants.paperJarFile(extension, "spigot"))
        }

        val decompileVanillaJarSpigot = tasks.register<DecompileVanillaJar>(Constants.DECOMPILE_VANILLA_JAR_SPIGOT) {
            dependsOn(remapVanillaJar)

            remapVanillaJar {
                inputJar = it.outputJar
            }

            fernFlowerJar = extension.craftBukkit.fernFlowerJar
            outputJar = cache.resolve(Constants.paperJarFile(extension, "spigot-decomp"))
        }

        val patchCraftBukkit = tasks.register<ApplyDiffPatches>(Constants.PATCH_CRAFTBUKKIT) {
            dependsOn(decompileVanillaJarSpigot)

            decompileVanillaJarSpigot {
                sourceJar = it.outputJar
            }

            baseDir = extension.craftBukkit.craftBukkitDir
            branch = "patched"

            patchDir = extension.craftBukkit.patchDir
            basePatchDir = extension.craftBukkit.sourceDir
            sourceBasePath = "net/minecraft/server"
        }

        val cloneSpigotApi = tasks.register<CloneRepo>(Constants.CLONE_SPIGOT_API) {
            repo = extension.craftBukkit.bukkitDir
            branch = "HEAD"

            sourceName = "Bukkit"
            targetName = "Spigot-API"
        }

        val patchSpigotApi = tasks.register<ApplyGitPatches>(Constants.PATCH_SPIGOT_API) {
            dependsOn(cloneSpigotApi)

            cloneSpigotApi {
                inputZip = it.outputZip
                targetName = it.targetName
            }

            patchDir = extension.spigot.bukkitPatchDir
        }

        val cloneSpigotServer = tasks.register<CloneRepo>(Constants.CLONE_SPIGOT_SERVER) {
            dependsOn(patchCraftBukkit)

            patchCraftBukkit {
                repo = it.baseDir
                branch = it.branch
            }

            sourceName = "CraftBukkit"
            targetName = "Spigot-Server"
        }

        val patchSpigotServer = tasks.register<ApplyGitPatches>(Constants.PATCH_SPIGOT_SERVER) {
            dependsOn(cloneSpigotServer)

            cloneSpigotServer {
                inputZip = it.outputZip
                targetName = it.targetName
            }

            patchDir = extension.spigot.craftBukkitPatchDir
        }

        val patchSpigot = tasks.register(Constants.PATCH_SPIGOT) {
            dependsOn(patchSpigotApi, patchSpigotServer)
        }

        val setupSpigotDependencyConfig = tasks.register<SetupSpigotDependencyConfig>(Constants.SETUP_SPIGOT_DEPENDENCY_CONFIG) {
            dependsOn(patchSpigotApi, patchSpigotServer)

            patchSpigotApi {
                spigotApiZip = it.outputZip
            }

            patchSpigotServer {
                spigotServerZip = it.outputZip
            }

            configurationName = Constants.SPIGOT_DEP_CONFIG
        }

        val remapVanillaJarSrg = tasks.register<RemapVanillaJarSrg>(Constants.REMAP_VANILLA_JAR_SRG) {
            dependsOn(filterVanillaJar, extractMcpData, generateSrgs)

            filterVanillaJar {
                inputJar = it.outputs.files.singleFile
            }

            extractMcpData {
                val dest = project.file(it.destinationDir)
                access = dest.resolve("config/access.txt")
                constructors = dest.resolve("config/constructors.txt")
                exceptions = dest.resolve("config/exceptions.txt")
            }

            generateSrgs {
                mappings = it.notchToSrg
            }

            outputJar = cache.resolve(Constants.paperJarFile(extension, "srg"))
        }

        val remapSpigotSources = tasks.register<RemapSources>(Constants.REMAP_SPIGOT_SOURCES) {
            dependsOn(
                extractMcpData,
                generateSpigotSrgs,
                downloadServerJar,
                remapVanillaJar,
                remapVanillaJarSrg,
                patchSpigotApi,
                patchSpigotServer,
                setupSpigotDependencyConfig
            )

            patchSpigotServer {
                inputZip = it.outputZip
            }

            extractMcpData {
                constructors = project.file(it.destinationDir).resolve("config/constructors.txt")
            }

            generateSpigotSrgs {
                spigotToSrg = it.spigotToSrg
            }

            downloadServerJar {
                vanillaJar = it.outputJar
            }

            remapVanillaJar {
                vanillaRemappedSpigotJar = it.outputJar
            }

            remapVanillaJarSrg {
                vanillaRemappedSrgJar = it.outputJar
            }

            patchSpigotApi {
                spigotApiZip = it.outputZip
            }

            setupSpigotDependencyConfig {
                config = it.configurationName
            }

            generatedAt = Constants.taskOutput(project, "spigot_at.cfg")
        }

        val remapSrgSourcesSpigot = tasks.register<RemapSrgSources>(Constants.REMAP_SRG_SOURCES_SPIGOT) {
            dependsOn(remapSpigotSources, mcpRewrites)

            remapSpigotSources {
                inputZip = it.outputZip
            }

            mcpRewrites {
                methodsCsv = it.methodsCsv
                fieldsCsv = it.fieldsCsv
                paramsCsv = it.paramsCsv
            }
        }

        val decompileVanillaJarForge = tasks.register<RunForgeFlower>(Constants.DECOMPILE_VANILLA_JAR_FORGE) {
            dependsOn(remapVanillaJarSrg)

            remapVanillaJarSrg {
                inputJar = it.outputJar
            }

            outputJar = cache.resolve(Constants.paperJarFile(extension, "srg-decomp"))
        }

        val applyMcpPatches = tasks.register<ApplyMcpPatches>(Constants.APPLY_MCP_PATCHES) {
            dependsOn(decompileVanillaJarForge)

            decompileVanillaJarForge {
                inputJar = it.outputJar
            }

            serverPatchDir = cache.resolve(Constants.mcpPatchesDir(extension))

            outputJar = cache.resolve(Constants.paperJarFile(extension, "srg-decomp-patched"))
        }

        val remapSrgSourcesSpigotVanilla = tasks.register<RemapSrgSources>(Constants.REMAP_SRG_SOURCES_VANILLA) {
            dependsOn(applyMcpPatches, mcpRewrites)

            applyMcpPatches {
                inputZip = it.outputJar
            }

            customOutputZip = cache.resolve(Constants.paperJarFile(extension, "mcp-decomp"))

            mcpRewrites {
                methodsCsv = it.methodsCsv
                fieldsCsv = it.fieldsCsv
                paramsCsv = it.paramsCsv
            }
        }
    }
}
