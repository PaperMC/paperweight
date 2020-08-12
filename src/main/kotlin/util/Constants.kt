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

package io.papermc.paperweight.util

import org.gradle.api.Task

object Constants {
    const val EXTENSION = "paperweight"

    const val MCP_MAPPINGS_CONFIG = "mcpConfig"

    const val MCP_DATA_CONFIG = "mcpData"
    const val SPIGOT_DEP_CONFIG = "spigotDeps"
    const val MINECRAFT_DEP_CONFIG = "minecraft"
    const val FORGE_FLOWER_CONFIG = "forgeFlower"
    const val MCINJECT_CONFIG = "mcinject"
    const val SPECIAL_SOURCE_CONFIG = "specialSource"

    const val FORGE_MAVEN_URL = "https://files.minecraftforge.net/maven"
    const val MC_LIBRARY_URL = "https://libraries.minecraft.net/"
    const val MC_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

    const val CACHE_PATH = "caches"
    private const val PAPER_PATH = "paperweight"

    const val MCP_DATA_DIR = "mcp/data"
    const val MCP_MAPPINGS_DIR = "mcp/mappings"
    const val SRG_DIR = "$MCP_MAPPINGS_DIR/srgs"

    const val PAPER_FIELDS_CSV = "$MCP_MAPPINGS_DIR/paper_fields.csv"
    const val PAPER_METHODS_CSV = "$MCP_MAPPINGS_DIR/paper_methods.csv"
    const val PAPER_PARAMS_CSV = "$MCP_MAPPINGS_DIR/paper_params.csv"

    const val NOTCH_TO_SRG = "$SRG_DIR/notch-srg.tsrg"
    const val NOTCH_TO_MCP = "$SRG_DIR/notch-mcp.tsrg"
    const val NOTCH_TO_SPIGOT = "$SRG_DIR/notch-spigot.tsrg"

    const val MCP_TO_NOTCH = "$SRG_DIR/mcp-notch.tsrg"
    const val MCP_TO_SRG = "$SRG_DIR/mcp-srg.tsrg"
    const val MCP_TO_SPIGOT = "$SRG_DIR/mcp-spigot.tsrg"

    const val SRG_TO_NOTCH = "$SRG_DIR/srg-notch.tsrg"
    const val SRG_TO_MCP = "$SRG_DIR/srg-mcp.tsrg"
    const val SRG_TO_SPIGOT = "$SRG_DIR/srg-spigot.tsrg"

    const val SPIGOT_TO_NOTCH = "$SRG_DIR/spigot-notch.tsrg"
    const val SPIGOT_TO_SRG = "$SRG_DIR/spigot-srg.tsrg"
    const val SPIGOT_TO_MCP = "$SRG_DIR/spigot-mcp.tsrg"

    const val MC_MANIFEST = "jsons/McManifest.json"
    const val VERSION_JSON = "jsons/McVersion.json"

    const val TASK_CACHE = "$PAPER_PATH/taskCache"

    fun Task.paperTaskOutput() = paperTaskOutput("jar")
    fun Task.paperTaskOutput(ext: String) = paperTaskOutput(name, ext)
    fun Task.paperTaskOutput(name: String, ext: String) = "$TASK_CACHE/$name.$ext"
}
