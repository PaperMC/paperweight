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

import org.gradle.api.Task

const val PAPERWEIGHT_EXTENSION = "paperweight"
const val PAPERWEIGHT_DEBUG = "paperweight.debug"
fun paperweightDebug(): Boolean = System.getProperty(PAPERWEIGHT_DEBUG, "false") == "true"
const val PAPERWEIGHT_VERBOSE_APPLY_PATCHES = "paperweight.verboseApplyPatches"

const val MC_LIBRARY_URL = "https://libraries.minecraft.net/"

const val MC_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

const val PAPER_MAVEN_REPO_URL = "https://repo.papermc.io/repository/maven-public/"

const val MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/"

const val PARAM_MAPPINGS_CONFIG = "paramMappings"
const val REMAPPER_CONFIG = "remapper"
const val PLUGIN_REMAPPER_CONFIG = "pluginRemapper"
const val DECOMPILER_CONFIG = "decompiler"
const val PAPERCLIP_CONFIG = "paperclip"
const val MACHE_CONFIG = "mache"
const val MACHE_CODEBOOK_CONFIG = "macheCodebook"
const val MACHE_REMAPPER_CONFIG = "macheRemapper"
const val MACHE_DECOMPILER_CONFIG = "macheDecompiler"
const val MACHE_PARAM_MAPPINGS_CONFIG = "macheParamMappings"
const val MACHE_CONSTANTS_CONFIG = "macheConstants"
const val MACHE_MINECRAFT_LIBRARIES_CONFIG = "macheMinecraftLibraries"
const val MACHE_MINECRAFT_CONFIG = "macheMinecraft"
const val MAPPED_JAR_OUTGOING_CONFIG = "mappedJarOutgoing"
const val JST_CONFIG = "javaSourceTransformer"
const val DEV_BUNDLE_CONFIG = "paperweightDevelopmentBundle"
const val MOJANG_MAPPED_SERVER_CONFIG = "mojangMappedServer"
const val MOJANG_MAPPED_SERVER_RUNTIME_CONFIG = "mojangMappedServerRuntime"
const val REOBF_CONFIG = "reobf"

const val PARAM_MAPPINGS_REPO_NAME = "paperweightParamMappingsRepository"
const val DECOMPILER_REPO_NAME = "paperweightDecompilerRepository"
const val REMAPPER_REPO_NAME = "paperweightRemapperRepository"
const val PLUGIN_REMAPPER_REPO_NAME = "paperweightPluginRemapperRepository"
const val MACHE_REPO_NAME = "paperweightMacheRepository"

const val CACHE_PATH = "caches"
private const val PAPER_PATH = "paperweight"

const val LOCK_DIR = "$PAPER_PATH/lock"
const val USERDEV_SETUP_LOCK = "$LOCK_DIR/userdev/setup.lock"

const val UPSTREAMS = "$PAPER_PATH/upstreams"
const val UPSTREAM_WORK_DIR_PROPERTY = "paperweightUpstreamWorkDir"

private const val JARS_PATH = "$PAPER_PATH/jars"
const val MINECRAFT_JARS_PATH = "$JARS_PATH/minecraft"
const val MINECRAFT_SOURCES_PATH = "$JARS_PATH/minecraft-sources"

const val PAPER_JARS_PATH = "$JARS_PATH/paper"
const val PAPER_SOURCES_JARS_PATH = "$JARS_PATH/paper-sources"

private const val MAPPINGS_DIR = "$PAPER_PATH/mappings"
const val SERVER_MAPPINGS = "$MAPPINGS_DIR/server_mappings.txt"
const val MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/official-mojang+yarn.tiny"

const val SPIGOT_MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/spigot-mojang+yarn.tiny"
const val OBF_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/official-spigot.tiny"
const val SPIGOT_MEMBER_MAPPINGS = "$MAPPINGS_DIR/spigot-members.csrg"
const val CLEANED_SPIGOT_MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/spigot-mojang+yarn-cleaned.tiny"
const val PATCHED_SPIGOT_MOJANG_YARN_MAPPINGS = "$MAPPINGS_DIR/spigot-mojang+yarn-patched.tiny"
const val REOBF_MOJANG_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/mojang+yarn-spigot-reobf.tiny"
const val PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/mojang+yarn-spigot-reobf-patched.tiny"
const val RELOCATED_PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS = "$MAPPINGS_DIR/mojang+yarn-spigot-reobf-patched-relocated.tiny"

const val OBF_NAMESPACE = "official"
const val SPIGOT_NAMESPACE = "spigot"
const val DEOBF_NAMESPACE = "mojang+yarn"
const val NEW_DEOBF_NAMESPACE = "mojang+parchment"
const val MAPPINGS_NAMESPACE_MANIFEST_KEY = "paperweight-mappings-namespace"

private const val DATA_PATH = "$PAPER_PATH/data"
const val MC_MANIFEST = "$DATA_PATH/McManifest.json"
const val VERSION_JSON = "$DATA_PATH/McVersion.json"

private const val BUNDLER_PATH = "$DATA_PATH/bundler"
const val SERVER_VERSION_JSON = "$BUNDLER_PATH/version.json"
const val SERVER_LIBRARIES_TXT = "$BUNDLER_PATH/ServerLibraries.txt"
const val SERVER_LIBRARIES_LIST = "$BUNDLER_PATH/libraries.list"
const val SERVER_VERSIONS_LIST = "$BUNDLER_PATH/versions.list"
const val SERVER_JAR = "$BUNDLER_PATH/server.jar"

private const val TASK_CACHE = "$PAPER_PATH/taskCache"

const val FINAL_REMAPPED_CODEBOOK_JAR = "$TASK_CACHE/codebook-minecraft.jar"
const val FINAL_DECOMPILE_JAR = "$TASK_CACHE/decompileJar.jar"

const val DOWNLOAD_SERVICE_NAME = "paperweightDownloadService"

private const val MACHE_PATH = "$PAPER_PATH/mache"
const val BASE_PROJECT = "$MACHE_PATH/base"

fun Task.paperTaskOutput(ext: String? = null) = paperTaskOutput(name, ext)
fun paperTaskOutput(name: String, ext: String? = null) = "$TASK_CACHE/$name" + (ext?.let { ".$it" } ?: "")

const val GENERAL_TASK_GROUP = "paperweight"
const val INTERNAL_TASK_GROUP = "paperweight internal"
