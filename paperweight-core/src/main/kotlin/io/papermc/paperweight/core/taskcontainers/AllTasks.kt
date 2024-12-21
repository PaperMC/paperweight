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
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class AllTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.coreExt,
    downloadService: Provider<DownloadService> = project.download
) : SpigotTasks(project) {

    val downloadMcLibrariesSources by tasks.registering<DownloadMcLibraries> {
        mcLibrariesFile.set(extractFromBundler.flatMap { it.serverLibrariesTxt })
        repositories.set(listOf(MC_LIBRARY_URL, MAVEN_CENTRAL_URL))
        outputDir.set(cache.resolve(MINECRAFT_SOURCES_PATH))
        sources.set(true)

        downloader.set(downloadService)
    }

    val downloadRuntimeClasspathSources by tasks.registering<DownloadPaperLibraries> {
        paperDependencies.set(
            project.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).map { configuration ->
                val view = configuration.incoming.artifactView {
                    componentFilter { it is ModuleComponentIdentifier }
                }
                view.artifacts
            }.map { artifacts ->
                artifacts.mapNotNull {
                    val id = it.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                    "${id.group}:${id.module}:${id.version}"
                }
            }
        )
        repositories.set(listOf(MAVEN_CENTRAL_URL, PAPER_MAVEN_REPO_URL))
        outputDir.set(cache.resolve(PAPER_SOURCES_JARS_PATH))
        sources.set(true)

        downloader.set(downloadService)
    }

    val generateReobfMappings by tasks.registering<GenerateReobfMappings> {
        inputMappings.set(patchMappings.flatMap { it.outputMappings })
        notchToSpigotMappings.set(generateSpigotMappings.flatMap { it.notchToSpigotMappings })
        sourceMappings.set(generateMappings.flatMap { it.outputMappings })
        // TODO: spigot uses javac now(?) so is this needed anymore?
        // spigotRecompiledClasses.set(remapSpigotSources.flatMap { it.spigotRecompiledClasses })

        reobfMappings.set(cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val patchReobfMappings by tasks.registering<PatchMappings> {
        inputMappings.set(generateReobfMappings.flatMap { it.reobfMappings })
        patch.set(extension.paper.reobfMappingsPatch.fileExists(project))

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        outputMappings.set(cache.resolve(PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val generateRelocatedReobfMappings by tasks.registering<GenerateRelocatedReobfMappings> {
        inputMappings.set(patchReobfMappings.flatMap { it.outputMappings })
        outputMappings.set(cache.resolve(RELOCATED_PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))
        craftBukkitPackageVersion.set(extension.spigot.packageVersion)
    }
}
