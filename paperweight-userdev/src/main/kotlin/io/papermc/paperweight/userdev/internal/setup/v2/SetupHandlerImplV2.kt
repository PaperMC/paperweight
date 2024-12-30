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

package io.papermc.paperweight.userdev.internal.setup.v2

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.PaperweightUserExtension
import io.papermc.paperweight.userdev.internal.action.*
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.setup.BundleInfo
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.UserdevSetupTask
import io.papermc.paperweight.userdev.internal.setup.action.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.artifacts.DependencySet
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*

class SetupHandlerImplV2(
    private val parameters: UserdevSetup.Parameters,
    private val bundle: BundleInfo<DevBundleV2.Config>,
) : SetupHandler {
    private fun createDispatcher(context: SetupHandler.ExecutionContext): WorkDispatcher {
        val dispatcher = WorkDispatcher.create(parameters.cache.path)
        dispatcher.overrideTerminalInputHash(parameters.bundleZipHash.get())

        val javaLauncher = javaLauncherValue(context.javaLauncher)
        val mcVer = StringValue(bundle.config.minecraftVersion)
        val bundleZip = fileValue(bundle.zip)
        dispatcher.provided(
            javaLauncher,
            mcVer,
            bundleZip,
        )

        val vanillaDownloads = dispatcher.register(
            "vanillaServerDownloads",
            VanillaServerDownloads(
                mcVer,
                dispatcher.outputFile("vanillaServer.jar"),
                dispatcher.outputFile("mojangServerMappings.txt"),
                parameters.downloadService.get(),
            )
        )

        class DownloadMcLibs(
            @Output
            val minecraftLibraryJars: DirectoryValue,
            @Input
            val vanillaServerLibraries: ListValue<String>,
        ) : WorkDispatcher.Action {
            override fun execute() {
                downloadLibraries(
                    download = parameters.downloadService,
                    workerExecutor = context.workerExecutor,
                    targetDir = minecraftLibraryJars.get(),
                    repositories = listOf(MC_LIBRARY_URL, MAVEN_CENTRAL_URL),
                    libraries = vanillaServerLibraries.get(),
                    sources = false
                ).await()
            }
        }

        val downloadMcLibs = dispatcher.register(
            "downloadMinecraftLibraries",
            DownloadMcLibs(
                dispatcher.outputDir("output"),
                stringListValue(bundle.config.buildData.vanillaServerLibraries),
            )
        )
        dispatcher.provided(downloadMcLibs.vanillaServerLibraries)

        val filterVanillaJar = dispatcher.register(
            "filterVanillaJar",
            FilterVanillaJarAction(
                vanillaDownloads.serverJar,
                stringListValue(bundle.config.buildData.vanillaJarIncludes),
                dispatcher.outputFile("output.jar"),
            )
        )
        dispatcher.provided(filterVanillaJar.includes)

        val generateMappings = dispatcher.register(
            "generateMappings",
            GenerateMappingsAction(
                javaLauncher,
                context.workerExecutor,
                vanillaDownloads.serverMappings,
                filterVanillaJar.outputJar,
                FileCollectionValue(context.paramMappingsConfig),
                downloadMcLibs.minecraftLibraryJars,
                dispatcher.outputFile("output.tiny"),
            )
        )
        dispatcher.provided(generateMappings.paramMappings)

        val remap = dispatcher.register(
            "remapMinecraft",
            RemapMinecraftAction(
                javaLauncher,
                stringListValue(bundle.config.remap.args),
                filterVanillaJar.outputJar,
                downloadMcLibs.minecraftLibraryJars,
                generateMappings.outputMappings,
                FileCollectionValue(context.remapperConfig),
                dispatcher.outputFile("output.jar"),
            )
        )
        dispatcher.provided(remap.minecraftRemapArgs)
        dispatcher.provided(remap.remapper)

        val fix = dispatcher.register(
            "fixMinecraftJar",
            FixMinecraftJarAction(
                javaLauncher,
                context.workerExecutor,
                remap.outputJar,
                dispatcher.outputFile("output.jar"),
                vanillaDownloads.serverJar,
                true,
            )
        )

        val at = dispatcher.register(
            "accessTransformMinecraft",
            AccessTransformMinecraftAction(
                javaLauncher,
                context.workerExecutor,
                bundleZip,
                StringValue(bundle.config.buildData.accessTransformFile),
                fix.outputJar,
                dispatcher.outputFile("output.jar"),
            )
        )
        dispatcher.provided(at.atPath)

        val decompile = dispatcher.register(
            "decompileMinecraftServer",
            DecompileMinecraftAction(
                javaLauncher,
                at.outputJar,
                dispatcher.outputFile("output.jar"),
                downloadMcLibs.minecraftLibraryJars,
                stringListValue(bundle.config.decompile.args),
                FileCollectionValue(context.decompilerConfig),
            )
        )
        dispatcher.provided(
            decompile.decompileArgs,
            decompile.decompiler,
        )

        val applyPatches = dispatcher.register(
            "applyDevBundlePatches",
            ApplyDevBundlePatchesAction(
                decompile.outputJar,
                bundleZip,
                StringValue(bundle.config.patchDir),
                dispatcher.outputFile("output.jar"),
            )
        )
        dispatcher.provided(applyPatches.patchesPath)

        val applyPaperclip = dispatcher.register(
            "applyPaperclipPatch",
            RunPaperclipAction(
                javaLauncher,
                bundleZip,
                StringValue(bundle.config.buildData.mojangMappedPaperclipFile),
                dispatcher.outputFile("output.jar"),
                vanillaDownloads.serverJar,
                mcVer,
                false,
            )
        )
        dispatcher.provided(applyPaperclip.paperclipPath)

        val filterPaperShadowJar = dispatcher.register(
            "filterPaperShadowJar",
            FilterPaperShadowJarAction(
                applyPatches.outputJar,
                applyPaperclip.outputJar,
                dispatcher.outputFile("output.jar"),
                value(bundle.config.buildData.relocations) {
                    listOf(InputStreamProvider.wrap(gson.toJson(it).byteInputStream()))
                },
            )
        )
        dispatcher.provided(filterPaperShadowJar.relocations)

        return dispatcher
    }

    override fun populateCompileConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        dependencySet.add(context.dependencyFactory.create(context.layout.files(context.setupTask.flatMap { it.mappedServerJar })))
        listOfNotNull(
            bundle.config.apiCoordinates,
            bundle.config.mojangApiCoordinates
        ).forEach { coordinate ->
            dependencySet.add(context.dependencyFactory.create(coordinate))
        }
        for (coordinates in bundle.config.buildData.libraryDependencies) {
            dependencySet.add(context.dependencyFactory.create(coordinates))
        }
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        dependencySet.add(context.dependencyFactory.create(context.layout.files(context.setupTask.flatMap { it.legacyPaperclipResult })))
    }

    @Volatile
    private var completedOutput: SetupHandler.ArtifactsResult? = null

    @Synchronized
    override fun generateArtifacts(context: SetupHandler.ExecutionContext): SetupHandler.ArtifactsResult {
        if (completedOutput != null) {
            return requireNotNull(completedOutput)
        }

        val dispatcher = createDispatcher(context)
        val filter = dispatcher.registered<FilterPaperShadowJarAction>("filterPaperShadowJar").outputJar
        val paperclip = dispatcher.registered<RunPaperclipAction>("applyPaperclipPatch").outputJar
        context.withProgressLogger { progressLogger ->
            dispatcher.dispatch(filter, paperclip) {
                progressLogger.progress(it)
            }
        }
        return SetupHandler.ArtifactsResult(filter.get(), paperclip.get())
            .also { completedOutput = it }
    }

    override fun extractReobfMappings(output: Path) {
        bundle.zip.openZipSafe().use { fs ->
            fs.getPath(bundle.config.buildData.reobfMappingsFile).copyTo(output, true)
        }
    }

    override fun afterEvaluate(context: SetupHandler.ConfigurationContext) {
        super.afterEvaluate(context)
        context.project.tasks.withType(UserdevSetupTask::class).configureEach {
            legacyPaperclipResult.set(layout.cache.resolve(paperTaskOutput("legacyPaperclipResult", "jar")))
        }
        context.project.extensions.configure<PaperweightUserExtension> {
            javaLauncher.convention(
                context.javaToolchainService.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(17))
                }
            )
        }
    }

    override val minecraftVersion: String
        get() = bundle.config.minecraftVersion

    override val pluginRemapArgs: List<String>
        get() = TinyRemapper.pluginRemapArgs // plugin remap args were not included in v2 bundles, if these change check this

    override val paramMappings: MavenDep
        get() = bundle.config.buildData.paramMappings

    override val decompiler: MavenDep
        get() = bundle.config.decompile.dep

    override val remapper: MavenDep
        get() = bundle.config.remap.dep

    override val mache: MavenDep?
        get() = null

    override val libraryRepositories: List<String>
        get() = bundle.config.buildData.libraryRepositories
}
