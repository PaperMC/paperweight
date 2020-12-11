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

package io.papermc.paperweight.ext

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory

open class PaperExtension(objects: ObjectFactory, layout: ProjectLayout) {
    @Suppress("MemberVisibilityCanBePrivate")
    val baseTargetDir: DirectoryProperty = objects.dirWithDefault(layout, ".")
    val spigotApiPatchDir: DirectoryProperty = objects.dirFrom(baseTargetDir, "Spigot-API-Patches")
    val spigotServerPatchDir: DirectoryProperty = objects.dirFrom(baseTargetDir, "Spigot-Server-Patches")
    val remappedSpigotServerPatchDir: DirectoryProperty = objects.dirFrom(baseTargetDir, "Spigot-Server-Patches-Remapped")
    val unmappedSpigotServerPatchDir: DirectoryProperty = objects.dirFrom(baseTargetDir, "Spigot-Server-Patches-Unmapped")
    val paperApiDir: DirectoryProperty = objects.dirFrom(baseTargetDir, "Paper-API")
    val paperServerDir: DirectoryProperty = objects.dirFrom(baseTargetDir, "Paper-Server")

    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataDir: DirectoryProperty = objects.dirWithDefault(layout, "build-data")
    val additionalSpigotClassMappings: RegularFileProperty = objects.fileProperty()
    val additionalSpigotMemberMappings: RegularFileProperty = objects.fileProperty()
    val libraryClassImports: RegularFileProperty = objects.fileFrom(buildDataDir, "library-imports.txt")
    val mappingsPatch: RegularFileProperty = objects.fileProperty()
}
