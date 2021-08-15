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

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.extension.RelocationExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class AllTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
) : SpigotTasks(project) {

    val mergeAdditionalAts by tasks.registering<MergeAccessTransforms> {
        firstFile.set(mergeGeneratedAts.flatMap { it.outputFile })
        secondFile.set(extension.paper.additionalAts.fileExists(project))
    }

    val applyMergedAt by tasks.registering<ApplyAccessTransform> {
        inputJar.set(fixJar.flatMap { it.outputJar })
        atFile.set(mergeAdditionalAts.flatMap { it.outputFile })
    }

    val copyResources by tasks.registering<CopyResources> {
        inputJar.set(applyMergedAt.flatMap { it.outputJar })
        vanillaJar.set(downloadServerJar.flatMap { it.outputJar })
        includes.set(listOf("/data/**", "/assets/**", "version.json", "yggdrasil_session_pubkey.der", "pack.mcmeta"))

        outputJar.set(cache.resolve(FINAL_REMAPPED_JAR))
    }

    val decompileJar by tasks.registering<RunForgeFlower> {
        executable.from(project.configurations.named(DECOMPILER_CONFIG))

        inputJar.set(copyResources.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })
    }

    val applyApiPatches by tasks.registering<ApplyGitPatches> {
        group = "paper"
        description = "Setup the Paper-API project"

        if (project.isBaseExecution) {
            outputs.upToDateWhen { false }
        }
        printOutput.set(project.isBaseExecution)

        branch.set("HEAD")
        upstreamBranch.set("upstream")
        upstream.set(patchSpigotApi.flatMap { it.outputDir })
        patchDir.set(extension.paper.spigotApiPatchDir)
        unneededFiles.value(listOf("README.md"))

        outputDir.set(extension.paper.paperApiDir)
    }

    @Suppress("DuplicatedCode")
    val applyServerPatches by tasks.registering<ApplyPaperPatches> {
        group = "paper"
        description = "Setup the Paper-Server project"

        if (project.isBaseExecution) {
            outputs.upToDateWhen { false }
        }
        printOutput.set(project.isBaseExecution)

        patchDir.set(extension.paper.spigotServerPatchDir)
        remappedSource.set(remapSpigotSources.flatMap { it.sourcesOutputZip })
        remappedTests.set(remapSpigotSources.flatMap { it.testsOutputZip })
        caseOnlyClassNameChanges.set(cleanupMappings.flatMap { it.caseOnlyNameChanges })
        upstreamDir.set(patchSpigotServer.flatMap { it.outputDir })
        sourceMcDevJar.set(decompileJar.flatMap { it.outputJar })
        mcLibrariesDir.set(downloadMcLibraries.flatMap { it.sourcesOutputDir })
        devImports.set(extension.paper.devImports.fileExists(project))
        unneededFiles.value(listOf("nms-patches", "applyPatches.sh", "CONTRIBUTING.md", "makePatches.sh", "README.md"))

        outputDir.set(extension.paper.paperServerDir)
        mcDevSources.set(extension.mcDevSourceDir)
    }

    val applyPatches by tasks.registering<Task> {
        group = "paper"
        description = "Set up the Paper development environment"
        dependsOn(applyApiPatches, applyServerPatches)
    }

    val rebuildApiPatches by tasks.registering<RebuildPaperPatches> {
        group = "paper"
        description = "Rebuilds patches to api"
        inputDir.set(extension.paper.paperApiDir)
        baseRef.set("base")

        patchDir.set(extension.paper.spigotApiPatchDir)
    }

    val rebuildServerPatches by tasks.registering<RebuildPaperPatches> {
        group = "paper"
        description = "Rebuilds patches to server"
        inputDir.set(extension.paper.paperServerDir)
        baseRef.set("base")

        patchDir.set(extension.paper.spigotServerPatchDir)
    }

    @Suppress("unused")
    val rebuildPatches by tasks.registering<Task> {
        group = "paper"
        description = "Rebuilds patches to api and server"
        dependsOn(rebuildApiPatches, rebuildServerPatches)
    }

    val generateReobfMappings by tasks.registering<GenerateReobfMappings> {
        inputMappings.set(patchMappings.flatMap { it.outputMappings })
        notchToSpigotMappings.set(generateSpigotMappings.flatMap { it.notchToSpigotMappings })
        sourceMappings.set(generateMappings.flatMap { it.outputMappings })

        reobfMappings.set(cache.resolve(REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val patchReobfMappings by tasks.registering<PatchMappings> {
        inputMappings.set(generateReobfMappings.flatMap { it.reobfMappings })
        patch.set(extension.paper.reobfMappingsPatch.fileExists(project))

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        outputMappings.set(cache.resolve(PATCHED_REOBF_MOJANG_SPIGOT_MAPPINGS))
    }

    val decompileRemapJar by tasks.registering<RunForgeFlower> {
        executable.from(project.configurations.named(DECOMPILER_CONFIG))

        inputJar.set(remapJar.flatMap { it.outputJar })
        libraries.from(downloadMcLibraries.map { it.outputDir.asFileTree })
    }

    val generateMojangMappedPaperclipPatch by tasks.registering<GeneratePaperclipPatch> {
        originalJar.set(downloadServerJar.flatMap { it.outputJar })
        mcVersion.set(extension.minecraftVersion)
    }

    val mojangMappedPaperclipJar by tasks.registering<Jar> {
        archiveClassifier.set("mojang-mapped-paperclip")
        with(tasks.named("jar", Jar::class).get())

        val paperclipConfig = project.configurations.named(PAPERCLIP_CONFIG)
        dependsOn(paperclipConfig, generateMojangMappedPaperclipPatch)

        val paperclipZip = project.zipTree(paperclipConfig.map { it.singleFile }.get())
        from(paperclipZip) {
            exclude("META-INF/MANIFEST.MF")
        }
        from(project.zipTree(generateMojangMappedPaperclipPatch.flatMap { it.outputZip }))

        manifest.from(paperclipZip.matching { include("META-INF/MANIFEST.MF") }.elements.map { it.single() })
    }

    @Suppress("unused")
    val generateDevelopmentBundle by tasks.registering<GenerateDevBundle> {
        // dependsOn(applyPatches)

        decompiledJar.set(decompileRemapJar.flatMap { it.outputJar })
        sourceDir.set(extension.paper.paperServerDir.path.resolve("src/main/java"))
        minecraftVersion.set(extension.minecraftVersion)
        mojangMappedPaperclipFile.set(mojangMappedPaperclipJar.flatMap { it.archiveFile })
        vanillaJarIncludes.set(extension.vanillaJarIncludes)
        vanillaServerLibraries.set(
            inspectVanillaJar.map { inspect ->
                inspect.serverLibraries.path.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            }
        )
        serverProject.set(extension.serverProject)
        relocations.set(extension.serverProject.flatMap { proj -> proj.the<RelocationExtension>().relocations.map { gson.toJson(it) } })
        reobfMappingsFile.set(patchReobfMappings.flatMap { it.outputMappings })

        paramMappingsConfig.set(project.configurations.named(PARAM_MAPPINGS_CONFIG))
        decompilerConfig.set(project.configurations.named(DECOMPILER_CONFIG))
        remapperConfig.set(project.configurations.named(REMAPPER_CONFIG))

        devBundleFile.set(project.layout.buildDirectory.file("libs/paperweight-development-bundle-${project.version}.zip"))
    }

    init {
        project.afterEvaluate {
            generateMojangMappedPaperclipPatch {
                patchedJar.set(extension.serverProject.get().tasks.named<FixJarForReobf>("fixJarForReobf").flatMap { it.inputJar })
            }
            generateDevelopmentBundle {
                val server = extension.serverProject.get()
                mappedServerCoordinates.set(
                    sequenceOf(
                        server.group,
                        server.name.toLowerCase(Locale.ENGLISH),
                        server.version
                    ).joinToString(":")
                )

                paramMappingsUrl.set(project.repositories.named<MavenArtifactRepository>(PARAM_MAPPINGS_REPO_NAME).get().url.toString())
                decompilerUrl.set(project.repositories.named<MavenArtifactRepository>(DECOMPILER_REPO_NAME).get().url.toString())
                remapperUrl.set(project.repositories.named<MavenArtifactRepository>(REMAPPER_REPO_NAME).get().url.toString())
            }
        }
    }
}
