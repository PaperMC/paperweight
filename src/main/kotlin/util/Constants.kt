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

    const val FORGE_MAVEN_URL = "https://files.minecraftforge.net/maven"
    const val MC_LIBRARY_URL = "https://libraries.minecraft.net/"
    const val MC_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

    const val YARN_CONFIG = "yarn"
    const val REMAPPER_CONFIG = "remapper"

    const val CACHE_PATH = "caches"
    private const val PAPER_PATH = "paperweight"

    private const val JARS_PATH = "$PAPER_PATH/jars"
    const val MINECRAFT_JARS_PATH = "$JARS_PATH/minecraft"
    const val MCP_TOOLS_PATH = "$JARS_PATH/tools"
    const val MCP_ZIPS_PATH = "$JARS_PATH/mcp"
    const val SPIGOT_JARS_PATH = "$JARS_PATH/spigot"

    const val MCP_DATA_DIR = "mcp/data"
    const val MCP_MAPPINGS_DIR = "mcp/mappings"
    const val SERVER_MAPPINGS = "$MCP_MAPPINGS_DIR/server_mappings.txt"
    const val SRG_DIR = "$MCP_MAPPINGS_DIR/srgs"

    const val MCP_CONFIG_JSON = "$MCP_DATA_DIR/config.json"

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
    const val MC_LIBRARIES = "jsons/McLibraries.txt"

    private const val TASK_CACHE = "$PAPER_PATH/taskCache"

    fun Task.paperTaskOutput(ext: String) = paperTaskOutput(name, ext)
    fun paperTaskOutput(name: String, ext: String) = "$TASK_CACHE/$name.$ext"
}
