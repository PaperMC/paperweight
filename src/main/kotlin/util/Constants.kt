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

package io.papermc.paperweight.util

import io.papermc.paperweight.ext.PaperweightExtension
import org.gradle.api.Project

internal object Constants {
    internal const val DEFAULT_STRING = "default_config_string"

    internal const val EXTENSION = "paperweight"

    internal const val SPIGOT_DEP_CONFIG = "spigotDeps"

    // Tasks
    internal const val GENERATE_SRGS = "generateSrgs"
    internal const val MCP_REWRITES = "mcpRewrites"
    internal const val GENERATE_SPIGOT_SRGS = "generateSpigotSrgs"
    internal const val DOWNLOAD_SERVER_JAR = "downloadServerJar"
    internal const val REMAP_VANILLA_JAR = "remapVanillaJar"
    internal const val FILTER_VANILLA_JAR = "filterVanillaJar"
    internal const val DECOMPILE_VANILLA_JAR_SPIGOT = "decompileVanillaJarSpigot"
    internal const val PATCH_CRAFTBUKKIT = "patchCraftBukkit"
    internal const val CLONE_SPIGOT_API = "cloneSpigotApi"
    internal const val PATCH_SPIGOT_API = "patchSpigotApi"
    internal const val CLONE_SPIGOT_SERVER = "cloneSpigotServer"
    internal const val PATCH_SPIGOT_SERVER = "patchSpigotServer"
    internal const val PATCH_SPIGOT = "patchSpigot"
    internal const val SETUP_SPIGOT_DEPENDENCY_CONFIG = "setupSpigotDependencyConfig"
    internal const val REMAP_SPIGOT_SOURCES = "remapSpigotSources"
    internal const val REMAP_SRG_SOURCES_SPIGOT = "remapSrgSourcesSpigot"
    internal const val REMAP_VANILLA_JAR_SRG = "remapVanillaJarSrg"
    internal const val DECOMPILE_VANILLA_JAR_FORGE = "decompileVanillaJarForge"
    internal const val APPLY_MCP_PATCHES = "applyMcpPatches"
    internal const val REMAP_SRG_SOURCES_VANILLA = "remapSrgSourcesSpigotVanilla"

    // Paths
    internal const val CACHE_PATH = "caches/minecraft"
    private const val MCP_PATH = "de/oceanlabs/mcp"

    private const val PAPER_PATH = "io/papermc/paperweight"
    private const val PAPER_VERSION_PATH = "$PAPER_PATH/versions"

    // Computed values
    internal fun mcpDataDir(extension: PaperweightExtension) = "$MCP_PATH/mcp/${extension.minecraftVersion}"
    internal fun mcpMappingDir(extension: PaperweightExtension) = "$MCP_PATH/mcp_${extension.mcpChannel}/${extension.mappingsVersion}"

    internal fun mcpFieldsCsv(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/fields.csv"
    internal fun mcpMethodsCsv(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/methods.csv"
    internal fun mcpParamsCsv(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/params.csv"

    internal fun paperMcpFieldsCsv(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/paper_fields.csv"
    internal fun paperMcpMethodsCsv(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/paper_methods.csv"
    internal fun paperMcpParamsCsv(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/paper_params.csv"

    internal fun notchToSrg(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-notch-srg.tsrg"
    internal fun notchToMcp(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-notch-mcp.tsrg"
    internal fun notchToSpigot(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-notch-spigot.tsrg"

    internal fun mcpToNotch(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-mcp-notch.tsrg"
    internal fun mcpToSrg(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-mcp-srg.tsrg"
    internal fun mcpToSpigot(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-mcp-spigot.tsrg"

    internal fun srgToNotch(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-srg-notch.tsrg"
    internal fun srgToMcp(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-srg-mcp.tsrg"
    internal fun srgToSpigot(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-srg-spigot.tsrg"

    internal fun spigotToNotch(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-spigot-notch.tsrg"
    internal fun spigotToSrg(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-spigot-srg.tsrg"
    internal fun spigotToMcp(extension: PaperweightExtension) = "${Constants.mcpMappingDir(extension)}/${extension.minecraftVersion}/srgs/paper-spigot-mcp.tsrg"

    internal fun spigotClassMappings(extension: PaperweightExtension) = "${extension.craftBukkit.mappingsDir}/${extension.buildDataInfo.classMappings}"
    internal fun spigotMemberMappings(extension: PaperweightExtension) = "${extension.craftBukkit.mappingsDir}/${extension.buildDataInfo.memberMappings}"
    internal fun spigotPackageMappings(extension: PaperweightExtension) = "${extension.craftBukkit.mappingsDir}/${extension.buildDataInfo.packageMappings}"

    internal fun paperVersionJson(extension: PaperweightExtension) = "$PAPER_VERSION_PATH/${extension.minecraftVersion}/McVersion.json"

    internal fun paperCache(extension: PaperweightExtension) = "$PAPER_VERSION_PATH/${extension.minecraftVersion}"
    internal fun paperJarFile(extension: PaperweightExtension, name: String) = "${paperCache(extension)}/$name.jar"

    internal fun mcpPatchesDir(extension: PaperweightExtension) = "$MCP_PATH/mcp/${extension.minecraftVersion}/patches/server"

    internal fun taskOutput(project: Project, name: String) = "${project.projectDir}/.gradle/taskOutputs/$name"

    internal fun taskOutput() = PaperTaskOutput
}
