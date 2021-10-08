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

package io.papermc.paperweight.core.taskcontainers

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project

@Suppress("MemberVisibilityCanBePrivate")
open class InitialTasks(
    project: Project,
    extension: PaperweightCoreExtension = project.ext,
) {

    private val mcManifest = project.downloadFile(MC_MANIFEST_URL, MINECRAFT_MANIFEST)
        .map { gson.fromJson<MinecraftManifest>(it) }

    private val versionManifest = project.downloadFile(
        mcManifest.zip(extension.minecraftVersion) { manifest, version ->
            manifest.versions.first { it.id == version }.url
        },
        MINECRAFT_VERSION_MANIFEST
    ).map { gson.fromJson<JsonObject>(it) }

    val minecraftLibrariesList = versionManifest.map { version ->
        version["libraries"].array.map { library ->
            library["name"].string
        }
    }

    val serverMappings = project.downloadFile(
        versionManifest.map { version ->
            version["downloads"]["server_mappings"]["url"].string
        },
        MOJANG_SERVER_MAPPINGS
    )

    val serverJar = project.downloadFile(
        versionManifest.map { version ->
            version["downloads"]["server"]["url"].string
        },
        VANILLA_SERVER_JAR
    )
}
