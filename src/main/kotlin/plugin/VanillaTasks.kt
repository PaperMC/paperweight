package io.papermc.paperweight.plugin

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.tasks.DownloadMcLibraries
import io.papermc.paperweight.tasks.FixJar
import io.papermc.paperweight.tasks.GenerateMappings
import io.papermc.paperweight.tasks.RemapJar
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.download
import io.papermc.paperweight.util.registering
import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer

@Suppress("MemberVisibilityCanBePrivate")
open class VanillaTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: File = project.layout.cache,
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
