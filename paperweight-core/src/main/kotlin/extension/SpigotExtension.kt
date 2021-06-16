/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.dirFrom
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory

open class SpigotExtension(objects: ObjectFactory, workDir: DirectoryProperty) {

    @Suppress("MemberVisibilityCanBePrivate")
    val spigotDir: DirectoryProperty = objects.dirFrom(workDir, "Spigot")
    val spigotApiDir: DirectoryProperty = objects.dirFrom(spigotDir, "Spigot-API")
    val spigotServerDir: DirectoryProperty = objects.dirFrom(spigotDir, "Spigot-Server")
    val bukkitPatchDir: DirectoryProperty = objects.dirFrom(spigotDir, "Bukkit-Patches")
    val craftBukkitPatchDir: DirectoryProperty = objects.dirFrom(spigotDir, "CraftBukkit-Patches")
}
