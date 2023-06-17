package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.McpConfig
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class McpConfigTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
    downloadService: Provider<DownloadService> = project.download
) : VanillaTasks(project) {

    val downloadMcpConfig by tasks.registering<DownloadMcpConfigTask> {
        repo.set(extension.mcpConfig.repo)
        artifact.set(extension.mcpConfig.artifact)

        output.set(cache.resolve(MCP_CONFIG_ZIP))
        config.set(cache.resolve(MCP_CONFIG_FILE))
        patches.set(cache.resolve(MCP_CONFIG_PATCHES))
        mappings.set(cache.resolve(MCP_CONFIG_MAPPINGS))

        downloader.set(downloadService)
    }
    private val mcpConfig = downloadMcpConfig.flatMap { it.config }.map { gson.fromJson<McpConfig>(it) }

    val createFernflowerLibraries by tasks.registering<CreateFernflowerLibraries> {
        input.set(extractFromBundler.flatMap { it.serverLibraryJars })
        output.set(cache.resolve(MCP_CONFIG_LIBS))
    }

    val runMcpConfigMerge by tasks.registering<RunMcpConfigMerge> {
        val mergeFunction = mcpConfig.map { it.functions.mergeMappings }
        executable.from(project.configurations.named(MCPCONFIG_MERGE_CONFIG))

        output.set(cache.resolve(MCP_CONFIG_MERGED_MAPPINGS))
        mappings.set(downloadMcpConfig.flatMap { it.mappings })
        official.set(downloadMappings.flatMap { it.outputFile })
        jvmargs.set(mergeFunction.map { it.jvmargs })
        args.set(mergeFunction.map { it.args })
    }

    val runMcpConfigRename by tasks.registering<RunMcpConfigRename> {
        val renameFunction = mcpConfig.map { it.functions.rename }
        executable.from(project.configurations.named(MCPCONFIG_RENAME_CONFIG))

        //input.set(filterVanillaJar.flatMap { it.outputJar })
        input.set(extractFromBundler.flatMap { it.serverJar })
        output.set(cache.resolve(FINAL_REMAPPED_JAR))
        mappings.set(runMcpConfigMerge.flatMap { it.output })
        libraries.set(createFernflowerLibraries.flatMap { it.output })
        jvmargs.set(renameFunction.map { it.jvmargs })
        args.set(renameFunction.map { it.args })
    }

    // adds @Override, but breaks patches
    //val fixJar by tasks.registering<FixJarTask> {
    //    inputJar.set(runMcpConfigRename.flatMap { it.output })
    //    vanillaJar.set(extractFromBundler.flatMap { it.serverJar })
    //}

    val runMcpConfigDecompile by tasks.registering<RunMcpConfigDecompile> {
        val decompileFunction = mcpConfig.map { it.functions.decompile }
        executable.from(project.configurations.named(MCPCONFIG_DECOMPILE_CONFIG))

        input.set(runMcpConfigRename.flatMap { it.output })
        output.set(cache.resolve(FINAL_DECOMPILE_JAR))
        libraries.set(createFernflowerLibraries.flatMap { it.output })
        jvmargs.set(decompileFunction.map { it.jvmargs })
        args.set(decompileFunction.map { it.args })
    }

    val generateSrgCsv by tasks.registering<GenerateSrgCsv> {
        // TODO temp, to speed up stuff
        ourMappings.set(generateMappings.flatMap { it.outputMappings })
        //ourMappings.set(cache.resolve(MOJANG_YARN_MAPPINGS))
        srgMappings.set(downloadMcpConfig.flatMap { it.mappings })

        outputCsv.set(cache.resolve(SRG_CSV))
    }

    val prepareBase by tasks.registering<PrepareBase> {
        input.set(runMcpConfigDecompile.flatMap { it.output })
        patches.set(downloadMcpConfig.flatMap { it.patches })
        // TODO temp, to speed up stuff
        mappings.set(generateSrgCsv.flatMap { it.outputCsv })
        //mappings.set(cache.resolve(SRG_CSV))
        output.set(cache.resolve(BASE_PROJECT))
    }
}
