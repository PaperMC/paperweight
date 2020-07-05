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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

open class PaperExtension(project: Project) {
    val spigotApiPatchDir: Property<String> = project.objects.property<String>().convention("Spigot-API-Patches")
    val spigotServerPatchDir: Property<String> = project.objects.property<String>().convention("Spigot-Server-Patches")
    val paperApiDir: Property<String> = project.objects.property<String>().convention("Paper-API")
    val paperServerDir: Property<String> = project.objects.property<String>().convention("Paper-Server")

    val mcpRewritesFile: RegularFileProperty = project.fileWithDefault("mcp/mcp-rewrites.txt")
    val missingEntriesSrgFile: RegularFileProperty = project.fileWithDefault("mcp/missing-spigot-class-mappings.csrg")
    val preMapSrgFile: RegularFileProperty = project.fileWithDefault("mcp/paper.srg")
    val removeListFile: RegularFileProperty = project.fileWithDefault("mcp/remove-list.txt")
    val memberMoveListFile: RegularFileProperty = project.fileWithDefault("mcp/member-moves.txt")

    init {
        spigotApiPatchDir.disallowUnsafeRead()
        spigotServerPatchDir.disallowUnsafeRead()
        paperApiDir.disallowUnsafeRead()
        paperServerDir.disallowUnsafeRead()

        mcpRewritesFile.disallowUnsafeRead()
        preMapSrgFile.disallowUnsafeRead()
        removeListFile.disallowUnsafeRead()
        memberMoveListFile.disallowUnsafeRead()
    }
}
