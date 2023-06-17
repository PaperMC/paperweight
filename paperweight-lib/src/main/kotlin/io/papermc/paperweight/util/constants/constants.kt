/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

package io.papermc.paperweight.util.constants

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Task

const val PAPERWEIGHT_EXTENSION = "paperweight"
const val PAPERWEIGHT_DEBUG = "paperweight.debug"

const val MC_LIBRARY_URL = "https://libraries.minecraft.net/"

const val MC_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

const val PAPER_MAVEN_REPO_URL = "https://repo.papermc.io/repository/maven-public/"

const val MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/"

const val FORGE_MAVEN_REPO_URL = "https://maven.minecraftforge.net/"

const val PARAM_MAPPINGS_CONFIG = "paramMappings"
const val REMAPPER_CONFIG = "remapper"
const val DECOMPILER_CONFIG = "decompiler"
const val PAPERCLIP_CONFIG = "paperclip"
const val DEV_BUNDLE_CONFIG = "paperweightDevelopmentBundle"
const val MCPCONFIG_RENAME_CONFIG = "mcpConfigRename"
const val MCPCONFIG_DECOMPILE_CONFIG = "mcpConfigDecompile"
const val MCPCONFIG_MERGE_CONFIG = "mcpConfigMerge"
const val MOJANG_MAPPED_SERVER_CONFIG = "mojangMappedServer"
const val MOJANG_MAPPED_SERVER_RUNTIME_CONFIG = "mojangMappedServerRuntime"
const val REOBF_CONFIG = "reobf"
const val CONSUMABLE_RUNTIME_CLASSPATH = "consumableRuntimeClasspath"
const val SERVER_RUNTIME_CLASSPATH = "serverRuntimeClasspath"

const val PARAM_MAPPINGS_REPO_NAME = "paperweightParamMappingsRepository"
const val DECOMPILER_REPO_NAME = "paperweightDecompilerRepository"
const val REMAPPER_REPO_NAME = "paperweightRemapperRepository"

const val CACHE_PATH = "caches"
private const val PAPER_PATH = "paperweight"

const val LOCK_DIR = "$PAPER_PATH/lock"
const val USERDEV_SETUP_LOCK = "$LOCK_DIR/userdev/setup.lock"
const val APPLY_PATCHES_LOCK_DIR = "$LOCK_DIR/apply-patches"

fun applyPatchesLock(targetDir: Path): String = APPLY_PATCHES_LOCK_DIR + '/' +
    targetDir.absolutePathString().hash(HashingAlgorithm.SHA256).asHexString() + ".lock"

const val UPSTREAMS = "$PAPER_PATH/upstreams"
const val UPSTREAM_WORK_DIR_PROPERTY = "paperweightUpstreamWorkDir"
const val PAPERWEIGHT_PREPARE_DOWNSTREAM = "prepareForDownstream"
const val PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY = "paperweightDownstreamDataFile"

private const val JARS_PATH = "$PAPER_PATH/jars"
const val MINECRAFT_JARS_PATH = "$JARS_PATH/minecraft"
const val MINECRAFT_SOURCES_PATH = "$JARS_PATH/minecraft-sources"

private const val MAPPINGS_DIR = "$PAPER_PATH/mappings"
const val SERVER_MAPPINGS = "$MAPPINGS_DIR/server_mappings.txt"
const val MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/official-mojang+yarn.tiny"
const val SRG_CSV = "$MAPPINGS_DIR/srg-mojang+yarn.csv"

const val REOBF_MOJANG_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/mojang+yarn-spigot-reobf.tiny"
const val PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/mojang+yarn-spigot-reobf-patched.tiny"

const val OBF_NAMESPACE = "official"
const val SPIGOT_NAMESPACE = "spigot"
const val DEOBF_NAMESPACE = "mojang+yarn"

private const val DATA_PATH = "$PAPER_PATH/data"
const val MC_MANIFEST = "$DATA_PATH/McManifest.json"
const val VERSION_JSON = "$DATA_PATH/McVersion.json"

private const val BUNDLER_PATH = "$DATA_PATH/bundler"
const val SERVER_VERSION_JSON = "$BUNDLER_PATH/version.json"
const val SERVER_LIBRARIES_TXT = "$BUNDLER_PATH/ServerLibraries.txt"
const val SERVER_LIBRARIES_LIST = "$BUNDLER_PATH/libraries.list"
const val SERVER_VERSIONS_LIST = "$BUNDLER_PATH/versions.list"

private const val PROJECTS_CACHE = "$PAPER_PATH/projects"
const val DECOMPILED_SRG = "$PROJECTS_CACHE/decompiled-srg"
const val RENAMED_MOJANG_YARN = "$PROJECTS_CACHE/renamed-mojang+yarn"

private const val SETUP_CACHE = "$PAPER_PATH/setupCache"
private const val TASK_CACHE = "$PAPER_PATH/taskCache"

const val FINAL_REMAPPED_JAR = "$TASK_CACHE/minecraft.jar"
const val FINAL_FILTERED_REMAPPED_JAR = "$TASK_CACHE/filteredMinecraft.jar"
const val FINAL_DECOMPILE_JAR = "$TASK_CACHE/decompileJar.jar"

const val MCP_CONFIG_DIR = "$TASK_CACHE/mcp-config"
const val MCP_CONFIG_ZIP = "$MCP_CONFIG_DIR/mcp-config.zip"
const val MCP_CONFIG_FILE = "$MCP_CONFIG_DIR/config.json"
const val MCP_CONFIG_LIBS = "$MCP_CONFIG_DIR/libraries.txt"
const val MCP_CONFIG_PATCHES = "$MCP_CONFIG_DIR/patches"
const val MCP_CONFIG_MAPPINGS = "$MCP_CONFIG_DIR/mappings.tsrg"
const val MCP_CONFIG_MERGED_MAPPINGS = "$MCP_CONFIG_DIR/merged-mappings.tsrg"

const val MC_DEV_SOURCES_DIR = "$PAPER_PATH/mc-dev-sources"

const val IVY_REPOSITORY = "$PAPER_PATH/ivyRepository"

const val RELOCATION_EXTENSION = "relocation"

fun paperSetupOutput(name: String, ext: String) = "$SETUP_CACHE/$name.$ext"
fun Task.paperTaskOutput(ext: String) = paperTaskOutput(name, ext)
fun paperTaskOutput(name: String, ext: String) = "$TASK_CACHE/$name.$ext"
