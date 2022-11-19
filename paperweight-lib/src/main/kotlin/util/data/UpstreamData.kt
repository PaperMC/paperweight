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

package io.papermc.paperweight.util.data

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*

data class UpstreamData(
    val vanillaJar: Path,
    val remappedJar: Path,
    val decompiledJar: Path,
    val mcVersion: String,
    val libDir: Path,
    val libSourceDir: Path,
    val libFile: Path,
    val spigotLibSourcesDir: Path,
    val mappings: Path,
    val notchToSpigotMappings: Path,
    val sourceMappings: Path,
    val reobfPackagesToFix: List<String>,
    val reobfMappingsPatch: Path,
    val vanillaIncludes: List<String>,
    val paramMappings: MavenDep,
    val accessTransform: Path,
    val spigotRecompiledClasses: Path,
    val bundlerVersionJson: Path,
    val serverLibrariesTxt: Path,
    val serverLibrariesList: Path
)

fun readUpstreamData(inputFile: Any): UpstreamData = inputFile.convertToPath().let { file ->
    try {
        if (file.isRegularFile()) {
            gson.fromJson(file)
        } else {
            throw PaperweightException("Upstream data file does not exist.")
        }
    } catch (ex: Exception) {
        throw PaperweightException("Failed to read upstream data.", ex)
    }
}
