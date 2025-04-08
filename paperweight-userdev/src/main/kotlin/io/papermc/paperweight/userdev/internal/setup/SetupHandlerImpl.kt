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

package io.papermc.paperweight.userdev.internal.setup

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.action.FileCollectionValue
import io.papermc.paperweight.userdev.internal.action.StringValue
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.action.fileValue
import io.papermc.paperweight.userdev.internal.action.javaLauncherValue
import io.papermc.paperweight.userdev.internal.action.stringListValue
import io.papermc.paperweight.userdev.internal.setup.action.ApplyDevBundlePatchesAction
import io.papermc.paperweight.userdev.internal.setup.action.ExtractFromBundlerAction
import io.papermc.paperweight.userdev.internal.setup.action.RunCodebookAction
import io.papermc.paperweight.userdev.internal.setup.action.RunPaperclipAction
import io.papermc.paperweight.userdev.internal.setup.action.SetupMacheSourcesAction
import io.papermc.paperweight.userdev.internal.setup.action.VanillaServerDownloads
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.*

class SetupHandlerImpl(
    private val parameters: UserdevSetup.Parameters,
    private val bundle: BundleInfo<GenerateDevBundle.DevBundleConfig>,
) : SetupHandler {
    private var macheMeta: MacheMeta? = null

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

        val applyPaperclip = dispatcher.register(
            "applyPaperclipPatch",
            RunPaperclipAction(
                javaLauncher,
                bundleZip,
                StringValue(bundle.config.mojangMappedPaperclipFile),
                dispatcher.outputFile("output.jar"),
                vanillaDownloads.serverJar,
                mcVer,
            )
        )
        dispatcher.provided(applyPaperclip.paperclipPath)

        val extract = dispatcher.register(
            "extractFromBundler",
            ExtractFromBundlerAction(
                vanillaDownloads.serverJar,
                dispatcher.outputFile("vanillaServer.jar"),
                dispatcher.outputDir("minecraftLibraries"),
            )
        )

        val remap = dispatcher.register(
            "remapMinecraft",
            RunCodebookAction(
                javaLauncher,
                stringListValue(macheMeta().remapperArgs),
                extract.vanillaServerJar,
                extract.minecraftLibraryJars,
                vanillaDownloads.serverMappings,
                FileCollectionValue(context.macheParamMappingsConfig),
                FileCollectionValue(context.macheConstantsConfig),
                FileCollectionValue(context.macheCodebookConfig),
                FileCollectionValue(context.macheRemapperConfig),
                dispatcher.outputFile("output.jar"),
            )
        )
        dispatcher.provided(
            remap.minecraftRemapArgs,
            remap.paramMappings,
            remap.constants,
            remap.codebook,
            remap.remapper,
        )

        val macheSources = dispatcher.register(
            "setupMacheSources",
            SetupMacheSourcesAction(
                javaLauncher,
                remap.outputJar,
                dispatcher.outputFile("output.zip"),
                extract.minecraftLibraryJars,
                stringListValue(macheMeta().decompilerArgs),
                FileCollectionValue(context.macheDecompilerConfig),
                FileCollectionValue(context.macheConfig),
            )
        )
        dispatcher.provided(
            macheSources.decompileArgs,
            macheSources.decompiler,
            macheSources.mache,
        )

        val applyPatches = dispatcher.register(
            "applyDevBundlePatches",
            ApplyDevBundlePatchesAction(
                macheSources.outputJar,
                bundleZip,
                StringValue(bundle.config.patchDir),
                dispatcher.outputFile("output.jar"),
                applyPaperclip.outputJar,
            )
        )
        dispatcher.provided(applyPatches.patchesPath)

        return dispatcher
    }

    override fun populateCompileConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        dependencySet.add(context.dependencyFactory.create(context.layout.files(context.setupTask.flatMap { it.mappedServerJar })))
        dependencySet.add(context.dependencyFactory.create(context.devBundleCoordinates))
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        populateCompileConfiguration(context, dependencySet)
    }

    @Volatile
    private var completedOutput: SetupHandler.ArtifactsResult? = null

    @Synchronized
    override fun generateArtifacts(context: SetupHandler.ExecutionContext): SetupHandler.ArtifactsResult {
        if (completedOutput != null) {
            return requireNotNull(completedOutput)
        }

        // If the config cache is reused then the mache config may not be populated
        setupMacheMeta(context.macheConfig)

        val dispatcher = createDispatcher(context)
        val request = if (parameters.genSources.get()) {
            dispatcher.registered<ApplyDevBundlePatchesAction>("applyDevBundlePatches").outputJar
        } else {
            dispatcher.registered<RunPaperclipAction>("applyPaperclipPatch").outputJar
        }
        context.withProgressLogger { progressLogger ->
            dispatcher.dispatch(request) {
                progressLogger.progress(it)
            }
        }
        return SetupHandler.ArtifactsResult(request.get(), null)
            .also { completedOutput = it }
    }

    override fun extractReobfMappings(output: Path) {
        bundle.config.reobfMappingsFile?.let { location ->
            bundle.zip.openZipSafe().use { fs ->
                fs.getPath(location).copyTo(output, true)
            }
        }
    }

    private fun macheMeta(): MacheMeta = requireNotNull(macheMeta) { "Mache meta is not setup yet" }

    private fun setupMacheMeta(macheFiles: FileCollection) {
        if (macheMeta == null) {
            synchronized(this) {
                macheMeta = macheFiles.resolveMacheMeta()
            }
        }
    }

    override fun afterEvaluate(context: SetupHandler.ConfigurationContext) {
        super.afterEvaluate(context)
        setupMacheMeta(context.project.configurations.getByName(MACHE_CONFIG))

        val configurations = context.project.configurations

        val macheCodebook = configurations.register(MACHE_CODEBOOK_CONFIG) {
            isTransitive = false
        }
        val macheRemapper = configurations.register(MACHE_REMAPPER_CONFIG) {
            isTransitive = false
        }
        val macheDecompiler = configurations.register(MACHE_DECOMPILER_CONFIG) {
            isTransitive = false
        }
        val macheParamMappings = configurations.register(MACHE_PARAM_MAPPINGS_CONFIG) {
            isTransitive = false
        }
        val macheConstants = configurations.register(MACHE_CONSTANTS_CONFIG) {
            isTransitive = false
        }

        context.project.tasks.withType(UserdevSetupTask::class).configureEach {
            macheCodebookConfig.from(macheCodebook)
            macheRemapperConfig.from(macheRemapper)
            macheDecompilerConfig.from(macheDecompiler)
            macheParamMappingsConfig.from(macheParamMappings)
            macheConstantsConfig.from(macheConstants)
        }

        macheMeta().addDependencies(context.project)
        macheMeta().addRepositories(context.project)
    }

    override val minecraftVersion: String
        get() = bundle.config.minecraftVersion

    override val pluginRemapArgs: List<String>
        get() = bundle.config.pluginRemapArgs

    override val paramMappings: MavenDep?
        get() = null

    override val decompiler: MavenDep?
        get() = null

    override val remapper: MavenDep?
        get() = null

    override val mache: MavenDep
        get() = bundle.config.mache

    override val libraryRepositories: List<String>
        get() = bundle.config.libraryRepositories
}
