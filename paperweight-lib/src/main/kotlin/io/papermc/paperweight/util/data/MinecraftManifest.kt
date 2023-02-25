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

package io.papermc.paperweight.util.data

import io.papermc.paperweight.util.*

data class MinecraftManifest(
    val latest: Map<String, *>,
    val versions: List<ManifestVersion>
)

data class ManifestVersion(
    val id: String,
    val type: String,
    val time: String,
    val releaseTime: String,
    val url: String,
    val sha1: String,
) {
    fun hash(): Hash = Hash(sha1, HashingAlgorithm.SHA1)
}

data class MinecraftVersionManifest(
    val downloads: Map<String, Download>,
) {
    data class Download(
        val sha1: String,
        val url: String,
    ) {
        fun hash(): Hash = Hash(sha1, HashingAlgorithm.SHA1)
    }

    fun download(name: String): Download {
        return downloads[name] ?: error("No such download '$name' in version manifest")
    }

    fun serverDownload(): Download = download("server")

    fun serverMappingsDownload(): Download = download("server_mappings")
}
