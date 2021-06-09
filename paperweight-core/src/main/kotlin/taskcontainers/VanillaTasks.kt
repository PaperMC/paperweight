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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.DownloadMcLibraries
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.download
import io.papermc.paperweight.util.registering
import io.papermc.paperweight.util.set
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class VanillaTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    downloadService: Provider<DownloadService> = project.download,
) : GeneralTasks(project) {

    val downloadMcLibraries by tasks.registering<DownloadMcLibraries> {
        mcLibrariesFile.set(setupMcLibraries.flatMap { it.outputFile })
        mcRepo.set(Constants.MC_LIBRARY_URL)
        outputDir.set(cache.resolve(Constants.MINECRAFT_JARS_PATH))
        sourcesOutputDir.set(cache.resolve(Constants.MINECRAFT_SOURCES_PATH))

        downloader.set(downloadService)
    }

    val generateMappings by tasks.registering<GenerateMappings> {
        vanillaJar.set(filterVanillaJar.flatMap { it.outputJar })
        librariesDir.set(downloadMcLibraries.flatMap { it.outputDir })

        vanillaMappings.set(downloadMappings.flatMap { it.outputFile })
        paramMappings.fileProvider(project.configurations.named(Constants.PARAM_MAPPINGS_CONFIG).map { it.singleFile })

        outputMappings.set(cache.resolve(Constants.MOJANG_YARN_MAPPINGS))
    }

    val remapJar by tasks.registering<RemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        mappingsFile.set(generateMappings.flatMap { it.outputMappings })
        fromNamespace.set(Constants.OBF_NAMESPACE)
        toNamespace.set(Constants.DEOBF_NAMESPACE)
        remapper.fileProvider(project.configurations.named(Constants.REMAPPER_CONFIG).map { it.singleFile })
    }

    val fixJar by tasks.registering<FixJar> {
        inputJar.set(remapJar.flatMap { it.outputJar })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
    }
}
