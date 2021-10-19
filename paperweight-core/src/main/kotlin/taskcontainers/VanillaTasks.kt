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

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
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
        mcRepo.set(MC_LIBRARY_URL)
        outputDir.set(cache.resolve(MINECRAFT_JARS_PATH))

        downloader.set(downloadService)
    }

    val inspectVanillaJar by tasks.registering<InspectVanillaJar> {
        inputJar.set(downloadServerJar.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })
        mcLibraries.set(setupMcLibraries.flatMap { it.outputFile })

        serverLibraries.set(cache.resolve(SERVER_LIBRARIES))
    }

    val downloadMcLibrariesSources by tasks.registering<DownloadMcLibraries> {
        mcLibrariesFile.set(inspectVanillaJar.flatMap { it.serverLibraries })
        mcRepo.set(MC_LIBRARY_URL)
        outputDir.set(cache.resolve(MINECRAFT_SOURCES_PATH))
        sources.set(true)

        downloader.set(downloadService)
    }

    val generateMappings by tasks.registering<GenerateMappings> {
        vanillaJar.set(filterVanillaJar.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })

        vanillaMappings.set(downloadMappings.flatMap { it.outputFile })
        paramMappings.fileProvider(project.configurations.named(PARAM_MAPPINGS_CONFIG).map { it.singleFile })

        outputMappings.set(cache.resolve(MOJANG_YARN_MAPPINGS))
    }

    val remapJar by tasks.registering<RemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        mappingsFile.set(generateMappings.flatMap { it.outputMappings })
        fromNamespace.set(OBF_NAMESPACE)
        toNamespace.set(DEOBF_NAMESPACE)
        remapper.from(project.configurations.named(REMAPPER_CONFIG))
    }

    val fixJar by tasks.registering<FixJar> {
        inputJar.set(remapJar.flatMap { it.outputJar })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
    }
}
