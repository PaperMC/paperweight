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

package io.papermc.paperweight.userdev.internal.setup.step

import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.setup.util.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import org.gradle.kotlin.dsl.*

class VanillaSteps(
    private val minecraftVersion: String,
    private val cache: Path,
    private val downloadService: DownloadService,
    private val bundleChanged: Boolean,
) {
    val minecraftVersionManifest: JsonObject by lazy { setupMinecraftVersionManifest() }
    val mojangJar: Path = cache.resolve(paperSetupOutput("downloadServerJar", "jar"))
    val serverMappings: Path = cache.resolve(SERVER_MAPPINGS)

    fun downloadVanillaServerJar(): DownloadResult<Unit> =
        downloadService.download(
            "vanilla minecraft server jar",
            minecraftVersionManifest["downloads"]["server"]["url"].string,
            mojangJar
        )

    fun downloadServerMappings(): DownloadResult<Unit> =
        downloadService.download(
            "mojang server mappings",
            minecraftVersionManifest["downloads"]["server_mappings"]["url"].string,
            serverMappings
        )

    private fun downloadMinecraftManifest(force: Boolean): DownloadResult<MinecraftManifest> =
        downloadService.download("minecraft manifest", MC_MANIFEST_URL, cache.resolve(MC_MANIFEST), force)
            .mapData { gson.fromJson(it.path) }

    private fun setupMinecraftVersionManifest(): JsonObject {
        var minecraftManifest = downloadMinecraftManifest(bundleChanged)
        if (!minecraftManifest.didDownload && minecraftManifest.data.versions.none { it.id == minecraftVersion }) {
            minecraftManifest = downloadMinecraftManifest(true)
        }

        val minecraftVersionManifestJson = downloadService.download(
            "minecraft version manifest",
            minecraftManifest.data.versions.firstOrNull { it.id == minecraftVersion }?.url
                ?: throw PaperweightException("Could not find Minecraft version '$minecraftVersion' in the downloaded manifest."),
            cache.resolve(VERSION_JSON)
        )
        return gson.fromJson(minecraftVersionManifestJson.path)
    }
}
