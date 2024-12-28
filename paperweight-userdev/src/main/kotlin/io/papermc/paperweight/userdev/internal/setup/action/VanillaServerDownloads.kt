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

package io.papermc.paperweight.userdev.internal.setup.action

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.StringValue
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class VanillaServerDownloads(
    @Input
    val minecraftVersion: StringValue,
    @Output
    val serverJar: FileValue,
    @Output
    val serverMappings: FileValue,
    private val downloadService: DownloadService,
) : WorkDispatcher.Action {
    override fun execute() {
        val tmp = createTempDirectory(serverJar.get().parent.createDirectories(), "vanilla-downloads")

        val mcManifestPath = tmp.resolve("mc.json")
        downloadService.downloadFile(MC_MANIFEST_URL, mcManifestPath, null)
        val mcManifest = gson.fromJson<MinecraftManifest>(mcManifestPath)

        val versionManifestPath = tmp.resolve("version.json")
        val ver = mcManifest.versions.firstOrNull { it.id == minecraftVersion.get() }
            ?: throw PaperweightException("Could not find Minecraft version '${minecraftVersion.get()}' in the downloaded manifest.")
        downloadService.downloadFile(
            ver.url,
            versionManifestPath,
            expectedHash = ver.hash()
        )
        val versionManifest: MinecraftVersionManifest = gson.fromJson(versionManifestPath)

        ioDispatcher("VanillaServerDownloads").use { dispatcher ->
            runBlocking {
                launch(dispatcher) {
                    val serverTmp = tmp.resolve("server.jar")
                    downloadService.downloadFile(
                        versionManifest.serverDownload().url,
                        serverTmp,
                        expectedHash = versionManifest.serverDownload().hash()
                    )
                    serverTmp.copyTo(serverJar.get(), overwrite = true)
                }
                launch(dispatcher) {
                    val mappingsTmp = tmp.resolve("mappings.txt")
                    downloadService.downloadFile(
                        versionManifest.serverMappingsDownload().url,
                        mappingsTmp,
                        expectedHash = versionManifest.serverMappingsDownload().hash()
                    )
                    mappingsTmp.copyTo(serverMappings.get(), overwrite = true)
                }
            }
        }

        tmp.deleteRecursive()
    }

    private fun DownloadService.downloadFile(remote: Any, destination: Path, expectedHash: Hash?) {
        destination.parent.createDirectories()
        destination.deleteIfExists()
        download(remote, destination, expectedHash)
    }
}
