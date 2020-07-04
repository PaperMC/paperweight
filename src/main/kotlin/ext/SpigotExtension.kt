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

open class SpigotExtension(project: Project) {
    var spigotDir: DirectoryProperty = project.dirWithDefault("work/Spigot")
    var spigotApiDir: DirectoryProperty = project.dirWithDefault("work/Spigot/Spigot-API")
    var spigotServerDir: DirectoryProperty = project.dirWithDefault("work/Spigot/Spigot-Server")
    var bukkitPatchDir: DirectoryProperty = project.dirWithDefault("work/Spigot/Bukkit-Patches")
    var craftBukkitPatchDir: DirectoryProperty = project.dirWithDefault("work/Spigot/CraftBukkit-Patches")

    init {
        spigotDir.disallowUnsafeRead()
        spigotApiDir.disallowUnsafeRead()
        spigotServerDir.disallowUnsafeRead()
        bukkitPatchDir.disallowUnsafeRead()
        craftBukkitPatchDir.disallowUnsafeRead()
    }
}
