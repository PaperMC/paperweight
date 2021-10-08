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

package io.papermc.paperweight.util

import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Provider

fun Project.downloadMinecraftLibraries(
    libs: List<String>
): Configuration = resolveWithRepos(
    libs,
    listOf(MC_LIBRARY_URL),
    MINECRAFT_LIBRARIES
)

fun Project.downloadMinecraftLibraries(
    libs: Provider<List<String>>
): Provider<Configuration> = libs.map {
    downloadMinecraftLibraries(it)
}

fun Project.downloadMinecraftLibrariesSources(
    libs: List<String>
): List<Path> = resolveWithRepos(
    // use sources classifier, attributes didn't work for some reason (probably no gradle meta on mojang repo)
    libs.map { line -> "$line:sources" },
    listOf(MC_LIBRARY_URL),
    MINECRAFT_LIBRARIES_SOURCES
).let { config ->
    // use lenientConfiguration as not all artifacts have sources
    config.resolvedConfiguration.lenientConfiguration.files.map { it.toPath() }
}

fun Project.downloadMinecraftLibrariesSources(
    libs: Provider<List<String>>
): Provider<List<Path>> = libs.map {
    downloadMinecraftLibrariesSources(it)
}
