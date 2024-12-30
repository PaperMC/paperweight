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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.core.util.coreExt
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class InitialTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.coreExt,
    downloadService: Provider<DownloadService> = project.download
) {

    val downloadMcManifest by tasks.registering<DownloadTask> {
        url.set(project.coreExt.minecraftManifestUrl)
        outputFile.set(cache.resolve(MC_MANIFEST))

        doNotTrackState("The Minecraft manifest is a changing resource")

        downloader.set(downloadService)
    }
    private val mcManifest = downloadMcManifest.flatMap { it.outputFile }.map { gson.fromJson<MinecraftManifest>(it) }

    val downloadMcVersionManifest by tasks.registering<CacheableDownloadTask> {
        url.set(
            mcManifest.zip(extension.minecraftVersion) { manifest, version ->
                manifest.versions.first { it.id == version }.url
            }
        )
        expectedHash.set(
            mcManifest.zip(extension.minecraftVersion) { manifest, version ->
                manifest.versions.first { it.id == version }.hash()
            }
        )
        outputFile.set(cache.resolve(VERSION_JSON))

        downloader.set(downloadService)
    }
    private val versionManifest = downloadMcVersionManifest.flatMap { it.outputFile }.map { gson.fromJson<MinecraftVersionManifest>(it) }

    val downloadMappings by tasks.registering<CacheableDownloadTask> {
        url.set(versionManifest.map { version -> version.serverMappingsDownload().url })
        expectedHash.set(versionManifest.map { version -> version.serverMappingsDownload().hash() })
        outputFile.set(cache.resolve(SERVER_MAPPINGS))

        downloader.set(downloadService)
    }

    val downloadServerJar by tasks.registering<DownloadServerJar> {
        downloadUrl.set(versionManifest.map { version -> version.serverDownload().url })
        expectedHash.set(versionManifest.map { version -> version.serverDownload().hash() })

        downloader.set(downloadService)
    }

    val extractFromBundler by tasks.registering<ExtractFromBundler> {
        bundlerJar.set(downloadServerJar.flatMap { it.outputJar })

        versionJson.set(cache.resolve(SERVER_VERSION_JSON))
        serverLibrariesTxt.set(cache.resolve(SERVER_LIBRARIES_TXT))
        serverLibrariesList.set(cache.resolve(SERVER_LIBRARIES_LIST))
        serverVersionsList.set(cache.resolve(SERVER_VERSIONS_LIST))
        serverLibraryJars.set(cache.resolve(MINECRAFT_JARS_PATH))
        serverJar.set(cache.resolve(SERVER_JAR))
    }

    val filterVanillaJar by tasks.registering<FilterJar> {
        inputJar.set(extractFromBundler.flatMap { it.serverJar })
        includes.set(extension.vanillaJarIncludes)
    }

    val generateMappings by tasks.registering<GenerateMappings> {
        vanillaJar.set(filterVanillaJar.flatMap { it.outputJar })
        libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })

        vanillaMappings.set(downloadMappings.flatMap { it.outputFile })

        outputMappings.set(cache.resolve(MOJANG_MAPPINGS))
    }
}
