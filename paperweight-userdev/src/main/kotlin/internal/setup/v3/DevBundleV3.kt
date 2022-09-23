/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

package io.papermc.paperweight.userdev.internal.setup.v3

import io.papermc.paperweight.extension.Relocation
import io.papermc.paperweight.userdev.internal.setup.DevBundleVersions
import io.papermc.paperweight.util.*

object DevBundleV3 {
    val version = DevBundleVersions.SupportedVersion(3, Config::class, SetupHandlerImplV3)

    data class Config(
        val minecraftVersion: String,
        val mappedServerCoordinates: String,
        val apiCoordinates: String,
        val mojangApiCoordinates: String,
        val buildData: BuildData,
        val decompile: Runner,
        val remapper: MavenDep,
        val patchDir: String
    )

    data class BuildData(
        val paramMappings: MavenDep,
        val reobfMappingsFile: String,
        val accessTransformFile: String,
        val mojangMappedPaperclipFile: String,
        val vanillaJarIncludes: List<String>,
        val compileDependencies: List<String>,
        val runtimeDependencies: List<String>,
        val libraryRepositories: List<String>,
        val relocations: List<Relocation>,
        val minecraftRemapArgs: List<String>,
        val pluginRemapArgs: List<String>,
    )

    data class Runner(val dep: MavenDep, val args: List<String>)
}
