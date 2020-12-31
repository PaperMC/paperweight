/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.util

import org.gradle.api.Task

object Constants {
    const val EXTENSION = "paperweight"

    const val FORGE_MAVEN_URL = "https://files.minecraftforge.net/maven/"
    const val FABRIC_MAVEN_URL = "https://maven.fabricmc.net/"
    const val MC_LIBRARY_URL = "https://libraries.minecraft.net/"

    const val MC_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

    const val PARAM_MAPPINGS_CONFIG = "paramMappings"
    const val REMAPPER_CONFIG = "remapper"
    const val DECOMPILER_CONFIG = "decompiler"

    const val CACHE_PATH = "caches"
    private const val PAPER_PATH = "paperweight"

    private const val JARS_PATH = "$PAPER_PATH/jars"
    const val MINECRAFT_JARS_PATH = "$JARS_PATH/minecraft"
    const val SPIGOT_JARS_PATH = "$JARS_PATH/spigot"

    private const val MAPPINGS_DIR = "$PAPER_PATH/mappings"
    const val SERVER_MAPPINGS = "$MAPPINGS_DIR/server_mappings.txt"
    const val MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/official-mojang+yarn.tiny"
    const val SPIGOT_MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/spigot-mojang+yarn.tiny"
    const val PATCHED_SPIGOT_MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/spigot-mojang+yarn-patch.tiny"
    const val PATCHED_MOJANG_YARN_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/mojang+yarn-spigot-patch.tiny"

    const val OBF_NAMESPACE = "official"
    const val SPIGOT_NAMESPACE = "spigot"
    const val DEOBF_NAMESPACE = "mojang+yarn"

    private const val DATA_PATH = "$PAPER_PATH/data"
    const val MC_MANIFEST = "$DATA_PATH/McManifest.json"
    const val VERSION_JSON = "$DATA_PATH/McVersion.json"
    const val MC_LIBRARIES = "$DATA_PATH/McLibraries.txt"

    private const val TASK_CACHE = "$PAPER_PATH/taskCache"

    const val FINAL_REMAPPED_JAR = "$TASK_CACHE/minecraft.jar"

    fun Task.paperTaskOutput(ext: String) = paperTaskOutput(name, ext)
    fun paperTaskOutput(name: String, ext: String) = "$TASK_CACHE/$name.$ext"
}
