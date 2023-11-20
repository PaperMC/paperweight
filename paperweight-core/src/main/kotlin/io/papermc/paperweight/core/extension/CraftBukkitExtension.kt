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

package io.papermc.paperweight.core.extension

import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory

open class CraftBukkitExtension(objects: ObjectFactory, workDir: DirectoryProperty) {

    val bukkitDir: DirectoryProperty = objects.dirFrom(workDir, "Bukkit")
    val craftBukkitDir: DirectoryProperty = objects.dirFrom(workDir, "CraftBukkit")
    val patchDir: DirectoryProperty = objects.dirFrom(craftBukkitDir, "nms-patches")

    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataDir: DirectoryProperty = objects.dirFrom(workDir, "BuildData")
    val buildDataInfo: RegularFileProperty = objects.fileFrom(buildDataDir, "info.json")
    val mappingsDir: DirectoryProperty = objects.dirFrom(buildDataDir, "mappings")
    val excludesFile: RegularFileProperty = objects.bukkitFileFrom(mappingsDir, "exclude")
    val atFile: RegularFileProperty = objects.bukkitFileFrom(mappingsDir, "at")

    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataBinDir: DirectoryProperty = objects.dirFrom(buildDataDir, "bin")
    val fernFlowerJar: RegularFileProperty = objects.fileFrom(buildDataBinDir, "fernflower.jar")
    val specialSourceJar: RegularFileProperty = objects.fileFrom(buildDataBinDir, "SpecialSource.jar")
    val specialSource2Jar: RegularFileProperty = objects.fileFrom(buildDataBinDir, "SpecialSource-2.jar")

    private fun ObjectFactory.bukkitFileFrom(base: DirectoryProperty, extension: String): RegularFileProperty = fileProperty().convention(
        base.flatMap { dir ->
            val file = dir.path.useDirectoryEntries { it.filter { f -> f.name.endsWith(extension) }.singleOrNull() }
            if (file != null) {
                mappingsDir.file(file.name)
            } else {
                fileProperty()
            }
        }
    )
}
