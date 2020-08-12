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

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

open class CraftBukkitExtension(project: Project, workDir: DirectoryProperty) {
    val bukkitDir: DirectoryProperty = project.dirFrom(workDir, "Bukkit")
    val craftBukkitDir: DirectoryProperty = project.dirFrom(workDir, "CraftBukkit")
    val patchDir: DirectoryProperty = project.dirFrom(craftBukkitDir, "nms-patches")
    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataDir: DirectoryProperty = project.dirFrom(workDir, "BuildData")
    val mappingsDir: DirectoryProperty = project.dirFrom(buildDataDir, "mappings")
    val excludesFile: RegularFileProperty = project.bukkitFileFrom(mappingsDir, "exclude")
    val atFile: RegularFileProperty = project.bukkitFileFrom(mappingsDir, "at")
    val buildDataInfo: RegularFileProperty = project.fileFrom(buildDataDir, "info.json")
    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataBinDir: DirectoryProperty = project.dirFrom(buildDataDir, "bin")
    val fernFlowerJar: RegularFileProperty = project.fileFrom(buildDataBinDir, "fernflower.jar")
    val specialSourceJar: RegularFileProperty = project.fileFrom(buildDataBinDir, "SpecialSource.jar")
    val specialSource2Jar: RegularFileProperty = project.fileFrom(buildDataBinDir, "SpecialSource-2.jar")

    private fun Project.bukkitFileFrom(base: DirectoryProperty, extension: String): RegularFileProperty  =
        objects.fileProperty().convention(base.flatMap { dir ->
            val file = dir.asFile.listFiles()?.firstOrNull { it.name.endsWith(extension) }
            if (file != null) {
                mappingsDir.file(file.name)
            } else {
                // empty
                objects.fileProperty()
            }
        })
}
