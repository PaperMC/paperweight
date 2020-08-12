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

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

open class PaperweightExtension(project: Project) {

    @Suppress("MemberVisibilityCanBePrivate")
    val workDir: DirectoryProperty = project.dirWithDefault("work")

    val minecraftVersion: Property<String> = project.objects.property()
    val mcpMinecraftVersion: Property<String> = project.objects.property<String>().convention(minecraftVersion)
    val mcpMappingsChannel: Property<String> = project.objects.property()
    val mcpMappingsVersion: Property<String> = project.objects.property()

    val mcpConfigFile: RegularFileProperty = project.objects.fileProperty().convention(null)

    val craftBukkit = CraftBukkitExtension(project, workDir)
    val spigot = SpigotExtension(project, workDir)
    val paper = PaperExtension(project)

    init {
        minecraftVersion.disallowUnsafeRead()
        mcpMinecraftVersion.disallowUnsafeRead()
        mcpMappingsChannel.disallowUnsafeRead()
        mcpMappingsVersion.disallowUnsafeRead()
    }

    fun craftBukkit(action: Action<in CraftBukkitExtension>) {
        action.execute(craftBukkit)
    }

    fun spigot(action: Action<in SpigotExtension>) {
        action.execute(spigot)
    }

    fun paper(action: Action<in PaperExtension>) {
        action.execute(paper)
    }
}
