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

package io.papermc.paperweight.userdev.internal.setup.v5

import io.papermc.paperweight.userdev.PaperweightUserExtension
import io.papermc.paperweight.userdev.internal.action.FileCollectionValue
import io.papermc.paperweight.userdev.internal.action.StringValue
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.action.fileValue
import io.papermc.paperweight.userdev.internal.action.javaLauncherValue
import io.papermc.paperweight.userdev.internal.action.stringListValue
import io.papermc.paperweight.userdev.internal.setup.BundleInfo
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.action.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.artifacts.DependencySet
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*

class SetupHandlerImplV5(
    private val parameters: UserdevSetup.Parameters,
    private val bundle: BundleInfo<DevBundleV5.Config>,
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

        val applyPaperclip = dispatcher.register(
            "applyPaperclipPatch",
            RunPaperclipAction(
                javaLauncher,
                bundleZip,
                StringValue(bundle.config.buildData.mojangMappedPaperclipFile),
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

        val filterVanillaJar = dispatcher.register(
            "filterVanillaJar",
            FilterVanillaJarAction(
                extract.vanillaServerJar,
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
                extract.minecraftLibraryJars,
                dispatcher.outputFile("output.tiny"),
            )
        )
        dispatcher.provided(generateMappings.paramMappings)

        val remap = dispatcher.register(
            "remapMinecraft",
            RemapMinecraftAction(
                javaLauncher,
                stringListValue(bundle.config.buildData.minecraftRemapArgs),
                filterVanillaJar.outputJar,
                extract.minecraftLibraryJars,
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
                extract.vanillaServerJar,
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
                extract.minecraftLibraryJars,
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
                applyPaperclip.outputJar,
            )
        )
        dispatcher.provided(applyPatches.patchesPath)

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
        for (coordinates in bundle.config.buildData.compileDependencies) {
            dependencySet.add(context.dependencyFactory.create(coordinates))
        }
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        dependencySet.add(context.dependencyFactory.create(context.layout.files(context.setupTask.flatMap { it.mappedServerJar })))
        listOfNotNull(
            bundle.config.apiCoordinates,
            bundle.config.mojangApiCoordinates
        ).forEach { coordinate ->
            val dep = context.dependencyFactory.create(coordinate).also {
                it.isTransitive = false
            }
            dependencySet.add(dep)
        }
        for (coordinates in bundle.config.buildData.runtimeDependencies) {
            val dep = context.dependencyFactory.create(coordinates).also {
                it.isTransitive = false
            }
            dependencySet.add(dep)
        }
    }

    @Volatile
    private var completedOutput: SetupHandler.ArtifactsResult? = null

    @Synchronized
    override fun generateArtifacts(context: SetupHandler.ExecutionContext): SetupHandler.ArtifactsResult {
        if (completedOutput != null) {
            return requireNotNull(completedOutput)
        }

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
        bundle.zip.openZipSafe().use { fs ->
            fs.getPath(bundle.config.buildData.reobfMappingsFile).copyTo(output, true)
        }
    }

    override fun afterEvaluate(context: SetupHandler.ConfigurationContext) {
        super.afterEvaluate(context)
        legacyDefaultJavaLauncher(context)
    }

    // Apply JDK 17 launcher as default for < 1.19.3
    private fun legacyDefaultJavaLauncher(context: SetupHandler.ConfigurationContext) {
        val versionParts = bundle.config.minecraftVersion.split('.', '-').mapNotNull { it.toIntOrNull() }
        if (versionParts[1] > 19) {
            return
        }
        if (versionParts[1] == 19 && versionParts[2] >= 3) {
            return
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

    override val deobfNamespace: String
        get() = LEGACY_DEOBF_NAMESPACE

    override val pluginRemapArgs: List<String>
        get() = bundle.config.buildData.pluginRemapArgs

    override val paramMappings: MavenDep
        get() = bundle.config.buildData.paramMappings

    override val decompiler: MavenDep
        get() = bundle.config.decompile.dep

    override val remapper: MavenDep
        get() = bundle.config.remapper

    override val mache: MavenDep?
        get() = null

    override val libraryRepositories: List<String>
        get() = bundle.config.buildData.libraryRepositories
}
