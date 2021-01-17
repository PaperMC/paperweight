/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

@file:Suppress("DuplicatedCode")

package io.papermc.paperweight

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.patchremap.ApplyAccessTransform
import io.papermc.paperweight.tasks.patchremap.RemapPatches
import io.papermc.paperweight.util.BuildDataInfo
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.MinecraftManifest
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.contents
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.fromJson
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.registering
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.registerIfAbsent

class Paperweight : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constants.EXTENSION, PaperweightExtension::class.java, target.objects, target.layout)

        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        target.tasks.register<Delete>("cleanCache") {
            group = "Paper"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        // Make sure the submodules are initialized
        Git(target.projectDir)("submodule", "update", "--init").execute()

        target.configurations.create(Constants.PARAM_MAPPINGS_CONFIG)
        target.configurations.create(Constants.REMAPPER_CONFIG)
        target.configurations.create(Constants.DECOMPILER_CONFIG)
        target.configurations.create(Constants.PAPERCLIP_CONFIG)

        target.repositories.apply {
            maven(Constants.FABRIC_MAVEN_URL) {
                content {
                    onlyForConfigurations(Constants.PARAM_MAPPINGS_CONFIG, Constants.REMAPPER_CONFIG)
                }
            }
            maven(Constants.FORGE_MAVEN_URL) {
                content {
                    onlyForConfigurations(Constants.DECOMPILER_CONFIG)
                }
            }
        }

        val tasks = target.createTasks()

        val generatePaperclipPatch by target.tasks.registering<GeneratePaperclipPatch>()

        // Setup the server jar
        target.afterEvaluate {
            target.ext.serverProject.forUseAtConfigurationTime().orNull?.setupServerProject(target, tasks.spigotTasks)?.let { reobfJar ->
                generatePaperclipPatch.configure {
                    originalJar.set(tasks.generalTasks.downloadServerJar.flatMap { it.outputJar })
                    patchedJar.set(reobfJar.flatMap { it.outputJar })
                    mcVersion.set(target.ext.minecraftVersion)
                }

                target.tasks.named("jar", Jar::class) {
                    val paperclipConfig = target.configurations.named(Constants.PAPERCLIP_CONFIG)
                    dependsOn(paperclipConfig, generatePaperclipPatch)

                    val paperclipZip = target.zipTree(paperclipConfig.map { it.singleFile })
                    from(paperclipZip) {
                        exclude("META-INF/MANIFEST.MF")
                    }
                    from(target.zipTree(generatePaperclipPatch.flatMap { it.outputZip }))

                    manifest.from(paperclipZip.matching { include("META-INF/MANIFEST.MF") }.singleFile)
                }
            }
        }
    }

    private fun Project.setupServerProject(parent: Project, spigotTasks: SpigotTasks): TaskProvider<RemapJarAtlas>? {
        if (!projectDir.exists()) {
            return null
        }

        val cache = parent.layout.cache

        plugins.apply("java")
        dependencies.apply {
            val remappedJar = cache.resolve(Constants.FINAL_REMAPPED_JAR)
            if (remappedJar.exists()) {
                add("implementation", parent.files(remappedJar))
            }

            val libsFile = cache.resolve(Constants.SERVER_LIBRARIES)
            if (libsFile.exists()) {
                libsFile.forEachLine { line ->
                    add("implementation", line)
                }
            }
        }

        apply(plugin = "com.github.johnrengelman.shadow")
        return createBuildTasks(parent.ext, spigotTasks)
    }

    private fun Project.createTasks(): TaskContainers {
        val extension = ext
        val cache: File = layout.cache

        val initialTasks = createInitialTasks()
        val generalTasks = createGeneralTasks()
        val vanillaTasks = createVanillaTasks(initialTasks, generalTasks)
        val spigotTasks = createSpigotTasks(generalTasks, vanillaTasks)

        val applyMergedAt by tasks.registering<ApplyAccessTransform> {
            inputJar.set(vanillaTasks.fixJar.flatMap { it.outputJar })
            atFile.set(spigotTasks.mergeGeneratedAts.flatMap { it.outputFile })
        }

        val copyResources by tasks.registering<CopyResources> {
            inputJar.set(applyMergedAt.flatMap { it.outputJar })
            vanillaJar.set(generalTasks.downloadServerJar.flatMap { it.outputJar })
            includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta"))

            outputJar.set(cache.resolve(Constants.FINAL_REMAPPED_JAR))
        }

        val decompileJar by tasks.registering<RunForgeFlower> {
            executable.fileProvider(configurations.named(Constants.DECOMPILER_CONFIG).map { it.singleFile })

            inputJar.set(copyResources.flatMap { it.outputJar })
            libraries.set(vanillaTasks.downloadMcLibraries.flatMap { it.outputDir })
        }

        val patchPaperApi by tasks.registering<ApplyGitPatches> {
            branch.set("HEAD")
            upstreamBranch.set("upstream")
            upstream.set(spigotTasks.patchSpigotApi.flatMap { it.outputDir })
            patchDir.set(extension.paper.spigotApiPatchDir)
            printOutput.set(true)

            outputDir.set(extension.paper.paperApiDir)
        }

        val patchPaperServer by tasks.registering<ApplyPaperPatches> {
            patchDir.set(extension.paper.spigotServerPatchDir)
            remappedSource.set(spigotTasks.remapSpigotSources.flatMap { it.outputZip })
            spigotServerDir.set(spigotTasks.patchSpigotServer.flatMap { it.outputDir })
            sourceMcDevJar.set(decompileJar.flatMap { it.outputJar })
            mcLibrariesDir.set(vanillaTasks.downloadMcLibraries.flatMap { it.sourcesOutputDir })
            libraryImports.set(extension.paper.libraryClassImports)

            outputDir.set(extension.paper.paperServerDir)
        }

        val patchPaper by tasks.registering<Task> {
            group = "Paper"
            description = "Set up the Paper development environment"
            dependsOn(patchPaperApi, patchPaperServer)
        }

        val rebuildPaperApi by tasks.registering<RebuildPaperPatches> {
            inputDir.set(extension.paper.paperApiDir)

            patchDir.set(extension.paper.spigotApiPatchDir)
        }

        val rebuildPaperServer by tasks.registering<RebuildPaperPatches> {
            inputDir.set(extension.paper.paperServerDir)
            server.set(true)

            patchDir.set(extension.paper.spigotServerPatchDir)
        }

        val rebuildPaperPatches by tasks.registering<Task> {
            group = "Paper"
            description = "Rebuilds patches to api and server"
            dependsOn(rebuildPaperApi, rebuildPaperServer)
        }

        createPatchRemapTasks(generalTasks, vanillaTasks, spigotTasks, applyMergedAt)

        return TaskContainers(initialTasks, generalTasks, vanillaTasks, spigotTasks)
    }

    // Shared task containers
    data class InitialTasks(
        val setupMcLibraries: TaskProvider<SetupMcLibraries>,
        val downloadMappings: TaskProvider<DownloadTask>
    )

    data class GeneralTasks(
        val buildDataInfo: Provider<BuildDataInfo>,
        val downloadServerJar: TaskProvider<DownloadServerJar>,
        val filterVanillaJar: TaskProvider<FilterJar>
    )

    data class VanillaTasks(
        val inspectVanillaJar: TaskProvider<InspectVanillaJar>,
        val generateMappings: TaskProvider<GenerateMappings>,
        val fixJar: TaskProvider<FixJar>,
        val downloadMcLibraries: TaskProvider<DownloadMcLibraries>
    )

    data class SpigotTasks(
        val patchMappings: TaskProvider<PatchMappings>,
        val spigotDecompileJar: TaskProvider<SpigotDecompileJar>,
        val patchSpigotApi: TaskProvider<ApplyGitPatches>,
        val patchSpigotServer: TaskProvider<ApplyGitPatches>,
        val remapSpigotSources: TaskProvider<RemapSources>,
        val mergeGeneratedAts: TaskProvider<MergeAccessTransforms>
    )

    data class TaskContainers(
        val initialTasks: InitialTasks,
        val generalTasks: GeneralTasks,
        val vanillaTasks: VanillaTasks,
        val spigotTasks: SpigotTasks
    )

    private fun Project.createInitialTasks(): InitialTasks {
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext
        val downloadService = download

        val downloadMcManifest by tasks.registering<DownloadTask> {
            url.set(Constants.MC_MANIFEST_URL)
            outputFile.set(cache.resolve(Constants.MC_MANIFEST))

            downloader.set(downloadService)
        }
        val mcManifest = downloadMcManifest.flatMap { it.outputFile }.map { gson.fromJson<MinecraftManifest>(it) }

        val downloadMcVersionManifest by tasks.registering<DownloadTask> {
            url.set(
                mcManifest.zip(extension.minecraftVersion) { manifest, version ->
                    manifest.versions.first { it.id == version }.url
                }
            )
            outputFile.set(cache.resolve(Constants.VERSION_JSON))

            downloader.set(downloadService)
        }
        val versionManifest = downloadMcVersionManifest.flatMap { it.outputFile }.map { gson.fromJson<JsonObject>(it) }

        val setupMcLibraries by tasks.registering<SetupMcLibraries> {
            dependencies.set(
                versionManifest.map { version ->
                    version["libraries"].array.map { library ->
                        library["name"].string
                    }
                }
            )
            outputFile.set(cache.resolve(Constants.MC_LIBRARIES))
        }

        val downloadMappings by tasks.registering<DownloadTask> {
            url.set(
                versionManifest.map { version ->
                    version["downloads"]["server_mappings"]["url"].string
                }
            )
            outputFile.set(cache.resolve(Constants.SERVER_MAPPINGS))

            downloader.set(downloadService)
        }

        return InitialTasks(setupMcLibraries, downloadMappings)
    }

    private fun Project.createGeneralTasks(): GeneralTasks {
        val downloadService = download

        val buildDataInfo: Provider<BuildDataInfo> = contents(ext.craftBukkit.buildDataInfo) {
            gson.fromJson(it)
        }

        val downloadServerJar by tasks.registering<DownloadServerJar> {
            downloadUrl.set(buildDataInfo.map { it.serverUrl })
            hash.set(buildDataInfo.map { it.minecraftHash })

            downloader.set(downloadService)
        }

        val filterVanillaJar by tasks.registering<FilterJar> {
            inputJar.set(downloadServerJar.flatMap { it.outputJar })
            includes.set(listOf("/*.class", "/net/minecraft/**"))
        }

        return GeneralTasks(buildDataInfo, downloadServerJar, filterVanillaJar)
    }

    private fun Project.createVanillaTasks(initialTasks: InitialTasks, generalTasks: GeneralTasks): VanillaTasks {
        val filterJar: TaskProvider<FilterJar> = generalTasks.filterVanillaJar
        val cache: File = layout.cache
        val downloadService = download

        val downloadMcLibraries by tasks.registering<DownloadMcLibraries> {
            mcLibrariesFile.set(initialTasks.setupMcLibraries.flatMap { it.outputFile })
            mcRepo.set(Constants.MC_LIBRARY_URL)
            outputDir.set(cache.resolve(Constants.MINECRAFT_JARS_PATH))
            sourcesOutputDir.set(cache.resolve(Constants.MINECRAFT_SOURCES_PATH))

            downloader.set(downloadService)
        }

        val inspectVanillaJar by tasks.registering<InspectVanillaJar> {
            inputJar.set(generalTasks.downloadServerJar.flatMap { it.outputJar })
            librariesDir.set(downloadMcLibraries.flatMap { it.outputDir })
            mcLibraries.set(initialTasks.setupMcLibraries.flatMap { it.outputFile })

            serverLibraries.set(cache.resolve(Constants.SERVER_LIBRARIES))
        }

        val generateMappings by tasks.registering<GenerateMappings> {
            vanillaJar.set(generalTasks.filterVanillaJar.flatMap { it.outputJar })

            vanillaMappings.set(initialTasks.downloadMappings.flatMap { it.outputFile })
            paramMappings.fileProvider(configurations.named(Constants.PARAM_MAPPINGS_CONFIG).map { it.singleFile })
            methodOverrides.set(inspectVanillaJar.flatMap { it.methodOverrides })

            outputMappings.set(cache.resolve(Constants.MOJANG_YARN_MAPPINGS))
        }

        val remapJar by tasks.registering<RemapJar> {
            inputJar.set(filterJar.flatMap { it.outputJar })
            mappingsFile.set(generateMappings.flatMap { it.outputMappings })
            fromNamespace.set(Constants.OBF_NAMESPACE)
            toNamespace.set(Constants.DEOBF_NAMESPACE)
            remapper.fileProvider(configurations.named(Constants.REMAPPER_CONFIG).map { it.singleFile })
        }

        val fixJar by tasks.registering<FixJar> {
            inputJar.set(remapJar.flatMap { it.outputJar })
            vanillaJar.set(generalTasks.downloadServerJar.flatMap { it.outputJar })
        }

        return VanillaTasks(inspectVanillaJar, generateMappings, fixJar, downloadMcLibraries)
    }

    private fun Project.createSpigotTasks(generalTasks: GeneralTasks, vanillaTasks: VanillaTasks): SpigotTasks {
        val cache: File = layout.cache
        val extension: PaperweightExtension = ext
        val downloadService = download

        val (buildDataInfo, downloadServerJar, filterVanillaJar) = generalTasks
        val (inspectVanillaJar, generateMappings, _, _) = vanillaTasks

        val addAdditionalSpigotMappings by tasks.registering<AddAdditionalSpigotMappings> {
            classSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberSrg.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            additionalClassEntriesSrg.set(extension.paper.additionalSpigotClassMappings)
            additionalMemberEntriesSrg.set(extension.paper.additionalSpigotMemberMappings)
        }

        val generateSpigotMappings by tasks.registering<GenerateSpigotMappings> {
            classMappings.set(addAdditionalSpigotMappings.flatMap { it.outputClassSrg })
            memberMappings.set(addAdditionalSpigotMappings.flatMap { it.outputMemberSrg })
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))

            loggerFields.set(inspectVanillaJar.flatMap { it.loggerFile })
            paramIndexes.set(inspectVanillaJar.flatMap { it.paramIndexes })
            syntheticMethods.set(inspectVanillaJar.flatMap { it.syntheticMethods })
            methodOverrides.set(inspectVanillaJar.flatMap { it.methodOverrides })

            sourceMappings.set(generateMappings.flatMap { it.outputMappings })

            outputMappings.set(cache.resolve(Constants.SPIGOT_MOJANG_YARN_MAPPINGS))
        }

        val patchMappings by tasks.registering<PatchMappings> {
            inputMappings.set(generateSpigotMappings.flatMap { it.outputMappings })
            patchMappings.set(extension.paper.mappingsPatch)

            outputMappings.set(cache.resolve(Constants.PATCHED_SPIGOT_MOJANG_YARN_MAPPINGS))
            outputMappingsReversed.set(cache.resolve(Constants.PATCHED_MOJANG_YARN_SPIGOT_MAPPINGS))
        }

        val spigotRemapJar by tasks.registering<SpigotRemapJar> {
            inputJar.set(filterVanillaJar.flatMap { it.outputJar })
            classMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.classMappings }))
            memberMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.memberMappings }))
            packageMappings.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.packageMappings }))
            accessTransformers.set(extension.craftBukkit.mappingsDir.file(buildDataInfo.map { it.accessTransforms }))

            workDirName.set(extension.craftBukkit.buildDataInfo.asFile.map { it.parentFile.parentFile.name })

            specialSourceJar.set(extension.craftBukkit.specialSourceJar)
            specialSource2Jar.set(extension.craftBukkit.specialSource2Jar)

            classMapCommand.set(buildDataInfo.map { it.classMapCommand })
            memberMapCommand.set(buildDataInfo.map { it.memberMapCommand })
            finalMapCommand.set(buildDataInfo.map { it.finalMapCommand })
        }

        val filterSpigotExcludes by tasks.registering<FilterSpigotExcludes> {
            inputZip.set(spigotRemapJar.flatMap { it.outputJar })
            excludesFile.set(extension.craftBukkit.excludesFile)
        }

        val spigotDecompileJar by tasks.registering<SpigotDecompileJar> {
            inputJar.set(filterSpigotExcludes.flatMap { it.outputZip })
            fernFlowerJar.set(extension.craftBukkit.fernFlowerJar)
            decompileCommand.set(buildDataInfo.map { it.decompileCommand })
        }

        val patchCraftBukkit by tasks.registering<ApplyDiffPatches> {
            sourceJar.set(spigotDecompileJar.flatMap { it.outputJar })
            sourceBasePath.set("net/minecraft/server")
            branch.set("patched")
            patchDir.set(extension.craftBukkit.patchDir)

            outputDir.set(extension.craftBukkit.craftBukkitDir)
        }

        val patchSpigotApi by tasks.registering<ApplyGitPatches> {
            branch.set("HEAD")
            upstreamBranch.set("upstream")
            upstream.set(extension.craftBukkit.bukkitDir)
            patchDir.set(extension.spigot.bukkitPatchDir)

            outputDir.set(extension.spigot.spigotApiDir)
        }

        val patchSpigotServer by tasks.registering<ApplyGitPatches> {
            branch.set(patchCraftBukkit.flatMap { it.branch })
            upstreamBranch.set("upstream")
            upstream.set(patchCraftBukkit.flatMap { it.outputDir })
            patchDir.set(extension.spigot.craftBukkitPatchDir)

            outputDir.set(extension.spigot.spigotServerDir)
        }

        val patchSpigot by tasks.registering<Task> {
            dependsOn(patchSpigotApi, patchSpigotServer)
        }

        val downloadSpigotDependencies by tasks.registering<DownloadSpigotDependencies> {
            dependsOn(patchSpigot)
            apiPom.set(patchSpigotApi.flatMap { it.outputDir.file("pom.xml") })
            serverPom.set(patchSpigotServer.flatMap { it.outputDir.file("pom.xml") })
            outputDir.set(cache.resolve(Constants.SPIGOT_JARS_PATH))

            downloader.set(downloadService)
        }

        val remapSpigotAt by tasks.registering<RemapSpigotAt> {
            inputJar.set(spigotRemapJar.flatMap { it.outputJar })
            mapping.set(patchMappings.flatMap { it.outputMappings })
            spigotAt.set(extension.craftBukkit.atFile)
        }

        val remapSpigotSources by tasks.registering<RemapSources> {
            spigotServerDir.set(patchSpigotServer.flatMap { it.outputDir })
            spigotApiDir.set(patchSpigotApi.flatMap { it.outputDir })
            mappings.set(patchMappings.flatMap { it.outputMappings })
            vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
            vanillaRemappedSpigotJar.set(filterSpigotExcludes.flatMap { it.outputZip })
            spigotDeps.set(downloadSpigotDependencies.flatMap { it.outputDir })
        }

        val remapGeneratedAt by tasks.registering<RemapAccessTransform> {
            inputFile.set(remapSpigotSources.flatMap { it.generatedAt })
            mappings.set(patchMappings.flatMap { it.outputMappings })
        }

        val mergeGeneratedAts by tasks.registering<MergeAccessTransforms> {
            inputFiles.add(remapGeneratedAt.flatMap { it.outputFile })
            inputFiles.add(remapSpigotAt.flatMap { it.outputFile })
        }

        return SpigotTasks(
            patchMappings,
            spigotDecompileJar,
            patchSpigotApi,
            patchSpigotServer,
            remapSpigotSources,
            mergeGeneratedAts
        )
    }

    private fun Project.createBuildTasks(ext: PaperweightExtension, spigotTasks: SpigotTasks): TaskProvider<RemapJarAtlas> {
        val shadowJar: TaskProvider<Jar> = tasks.named("shadowJar", Jar::class.java)

        val reobfJar by tasks.registering<RemapJarAtlas> {
            dependsOn(shadowJar)
            inputJar.fileProvider(shadowJar.map { it.outputs.files.singleFile })

            mappingsFile.set(spigotTasks.patchMappings.flatMap { it.outputMappingsReversed })
            packageVersion.set(ext.versionPackage)
            fromNamespace.set(Constants.DEOBF_NAMESPACE)
            toNamespace.set(Constants.SPIGOT_NAMESPACE)

            outputJar.set(buildDir.resolve("libs/${shadowJar.get().archiveBaseName.get()}-reobf.jar"))
        }

        return reobfJar
    }

    private fun Project.createPatchRemapTasks(
        generalTasks: GeneralTasks,
        vanillaTasks: VanillaTasks,
        spigotTasks: SpigotTasks,
        applyMergedAt: TaskProvider<ApplyAccessTransform>
    ) {
        val extension: PaperweightExtension = ext

        /*
         * To ease the waiting time for debugging this task, all of the task dependencies have been removed (notice all
         * of those .get() calls). This means when you make changes to paperweight Gradle won't know that this task
         * technically depends on the output of all of those other tasks.
         *
         * In order to run all of the other necessary tasks before running this task in order to setup the inputs, run:
         *
         *   ./gradlew patchPaper applyVanillaSrgAt
         *
         * Then you should be able to run `./gradlew remapPatches` without having to worry about all of the other tasks
         * running whenever you make changes to paperweight.
         */
        val remapPatches by tasks.registering<RemapPatches> {
            group = "Paper"
            description = "EXPERIMENTAL & BROKEN: Attempt to remap Paper's patches from Spigot mappings to SRG."

            inputPatchDir.set(extension.paper.unmappedSpigotServerPatchDir)
            apiPatchDir.set(extension.paper.spigotApiPatchDir)

            mappingsFile.set(spigotTasks.patchMappings.flatMap { it.outputMappings }.get())
            ats.set(spigotTasks.remapSpigotSources.flatMap { it.generatedAt }.get())

            // Pull in as many jars as possible to reduce the possibility of type bindings not resolving
            classpathJars.add(applyMergedAt.flatMap { it.outputJar }.get()) // final remapped jar
            classpathJars.add(spigotTasks.remapSpigotSources.flatMap { it.vanillaRemappedSpigotJar }.get()) // Spigot remapped jar
            classpathJars.add(generalTasks.downloadServerJar.flatMap { it.outputJar }.get()) // pure vanilla jar

            spigotApiDir.set(spigotTasks.patchSpigotApi.flatMap { it.outputDir }.get())
            spigotServerDir.set(spigotTasks.patchSpigotServer.flatMap { it.outputDir }.get())
            spigotDecompJar.set(spigotTasks.spigotDecompileJar.flatMap { it.outputJar }.get())

            // library class imports
            mcLibrariesDir.set(vanillaTasks.downloadMcLibraries.flatMap { it.outputDir }.get())
            libraryImports.set(extension.paper.libraryClassImports)

            outputPatchDir.set(extension.paper.remappedSpigotServerPatchDir)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val Project.download: Provider<DownloadService>
        get() = gradle.sharedServices.registrations.getByName("download").service as Provider<DownloadService>
}
