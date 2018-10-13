/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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

open class CraftBukkitExtension(project: Project) {
    val bukkitDir: DirectoryProperty = project.dirWithDefault("work/Bukkit")
    var craftBukkitDir: DirectoryProperty = project.dirWithDefault("work/CraftBukkit")
    var patchDir: DirectoryProperty = project.dirWithDefault("work/CraftBukkit/nms-patches")
    var mappingsDir: DirectoryProperty = project.dirWithDefault("work/BuildData/mappings")
    var buildDataInfo: RegularFileProperty = project.fileWithDefault("work/BuildData/info.json")
    var fernFlowerJar: RegularFileProperty = project.fileWithDefault("work/BuildData/bin/fernflower.jar")
    var specialSourceJar: RegularFileProperty = project.fileWithDefault("work/BuildData/bin/SpecialSource.jar")
    var specialSource2Jar: RegularFileProperty = project.fileWithDefault("work/BuildData/bin/SpecialSource-2.jar")
}
