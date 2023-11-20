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

package io.papermc.paperweight.userdev.internal.setup.step

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
    private val versionManifest: MinecraftVersionManifest by lazy { setupMinecraftVersionManifest() }
    val mojangJar: Path = cache.resolve(paperSetupOutput("downloadServerJar", "jar"))
    val serverMappings: Path = cache.resolve(SERVER_MAPPINGS)

    fun downloadVanillaServerJar(): DownloadResult<Unit> = downloadService.download(
        "vanilla minecraft server jar",
        versionManifest.serverDownload().url,
        mojangJar,
        expectedHash = versionManifest.serverDownload().hash()
    )

    fun downloadServerMappings(): DownloadResult<Unit> = downloadService.download(
        "mojang server mappings",
        versionManifest.serverMappingsDownload().url,
        serverMappings,
        expectedHash = versionManifest.serverMappingsDownload().hash()
    )

    private fun downloadMinecraftManifest(force: Boolean): DownloadResult<MinecraftManifest> =
        downloadService.download("minecraft manifest", MC_MANIFEST_URL, cache.resolve(MC_MANIFEST), force)
            .mapData { gson.fromJson(it.path) }

    private fun setupMinecraftVersionManifest(): MinecraftVersionManifest {
        var minecraftManifest = downloadMinecraftManifest(bundleChanged)
        if (!minecraftManifest.didDownload && minecraftManifest.data.versions.none { it.id == minecraftVersion }) {
            minecraftManifest = downloadMinecraftManifest(true)
        }

        val ver = minecraftManifest.data.versions.firstOrNull { it.id == minecraftVersion }
            ?: throw PaperweightException("Could not find Minecraft version '$minecraftVersion' in the downloaded manifest.")
        val minecraftVersionManifestJson = downloadService.download(
            "minecraft version manifest",
            ver.url,
            cache.resolve(VERSION_JSON),
            expectedHash = ver.hash()
        )
        return gson.fromJson(minecraftVersionManifestJson.path)
    }
}
