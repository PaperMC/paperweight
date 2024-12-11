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
import io.papermc.paperweight.userdev.internal.setup.step.*
import io.papermc.paperweight.userdev.internal.setup.util.lockSetup
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.*

class SetupHandlerImpl(
    private val parameters: UserdevSetup.Parameters,
    private val bundle: ExtractedBundle<GenerateDevBundle.DevBundleConfig>,
    private val cache: Path = parameters.cache.path,
) : SetupHandler {
    private var macheMeta: MacheMeta? = null
    private val vanillaSteps by lazy {
        VanillaSteps(
            bundle.config.minecraftVersion,
            cache,
            parameters.downloadService.get(),
            bundle.changed,
        )
    }
    private val vanillaServerJar: Path = cache.resolve(paperSetupOutput("vanillaServerJar", "jar"))
    private val minecraftLibraryJars = cache.resolve(MINECRAFT_JARS_PATH)
    private val mappedServerJar: Path = cache.resolve(paperSetupOutput("remapServerJar", "jar"))
    private val baseSources: Path = cache.resolve(paperSetupOutput("baseSources", "jar"))
    private val patchedSourcesJar: Path = cache.resolve(paperSetupOutput("patchedSources", "jar"))
    private val mojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("applyMojangMappedPaperclipPatch", "jar"))

    private fun minecraftLibraryJars(): List<Path> = minecraftLibraryJars.filesMatchingRecursive("*.jar")

    private fun generateSources(context: SetupHandler.ExecutionContext) {
        setupMacheMeta(context.macheConfig) // If the config cache is reused then the mache config may not be populated
        vanillaSteps.downloadVanillaServerJar()
        vanillaSteps.downloadServerMappings()
        applyMojangMappedPaperclipPatch(context)

        val extractStep = createExtractFromBundlerStep()

        val remapStep = RemapMinecraftMache.create(
            context,
            macheMeta().remapperArgs,
            vanillaServerJar,
            ::minecraftLibraryJars,
            vanillaSteps.serverMappings,
            mappedServerJar,
            cache,
        )

        val macheSourcesStep = MinecraftSourcesMache.create(
            context,
            mappedServerJar,
            baseSources,
            cache,
            ::minecraftLibraryJars,
            macheMeta().decompilerArgs,
        )

        val applyDevBundlePatchesStep = ApplyDevBundlePatches(
            baseSources,
            bundle.dir.resolve(bundle.config.patchDir),
            patchedSourcesJar,
            mojangMappedPaperJar
        )

        StepExecutor.executeSteps(
            bundle.changed,
            context,
            extractStep,
            remapStep,
            macheSourcesStep,
            applyDevBundlePatchesStep,
        )
    }

    // This can be called when a user queries the server jar provider in
    // PaperweightUserExtension, possibly by a task running in a separate
    // thread to dependency resolution.
    @Synchronized
    private fun applyMojangMappedPaperclipPatch(context: SetupHandler.ExecutionContext) {
        if (setupCompleted) {
            return
        }

        lockSetup(cache, true) {
            StepExecutor.executeStep(
                context,
                RunPaperclip(
                    bundle.dir.resolve(bundle.config.mojangMappedPaperclipFile),
                    mojangMappedPaperJar,
                    vanillaSteps.mojangJar,
                    minecraftVersion,
                )
            )
        }
    }

    override fun populateCompileConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        dependencySet.add(context.dependencyFactory.create(context.layout.files(context.setupTask.flatMap { it.mappedServerJar })))
        dependencySet.add(context.dependencyFactory.create(context.devBundleCoordinates))
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.ConfigurationContext, dependencySet: DependencySet) {
        populateCompileConfiguration(context, dependencySet)
    }

    private var setupCompleted = false

    @Synchronized
    override fun combinedOrClassesJar(context: SetupHandler.ExecutionContext): Path {
        if (setupCompleted) {
            return if (parameters.genSources.get()) {
                patchedSourcesJar
            } else {
                mojangMappedPaperJar
            }
        }

        val ret = lockSetup(cache) {
            if (parameters.genSources.get()) {
                generateSources(context)
                patchedSourcesJar
            } else {
                vanillaSteps.downloadVanillaServerJar()
                StepExecutor.executeStep(context, createExtractFromBundlerStep())
                applyMojangMappedPaperclipPatch(context)
                mojangMappedPaperJar
            }
        }

        setupCompleted = true

        return ret
    }

    private fun macheMeta(): MacheMeta = requireNotNull(macheMeta) { "Mache meta is not setup yet" }

    private fun setupMacheMeta(macheFiles: FileCollection) {
        if (macheMeta == null) {
            synchronized(this) {
                macheMeta = macheFiles.resolveMacheMeta()
            }
        }
    }

    override fun afterEvaluate(project: Project) {
        super.afterEvaluate(project)
        setupMacheMeta(project.configurations.getByName(MACHE_CONFIG))

        val configurations = project.configurations

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

        project.tasks.withType(UserdevSetupTask::class).configureEach {
            if (parameters.genSources.get()) {
                mappedServerJar.set(patchedSourcesJar)
            } else {
                mappedServerJar.set(mojangMappedPaperJar)
            }

            macheCodebookConfig.from(macheCodebook)
            macheRemapperConfig.from(macheRemapper)
            macheDecompilerConfig.from(macheDecompiler)
            macheParamMappingsConfig.from(macheParamMappings)
            macheConstantsConfig.from(macheConstants)
        }

        macheMeta().addDependencies(project)
        macheMeta().addRepositories(project)
    }

    override val reobfMappings: Path
        get() = bundle.dir.resolve(bundle.config.reobfMappingsFile)

    override val minecraftVersion: String
        get() = bundle.config.minecraftVersion

    override val pluginRemapArgs: List<String>
        get() = bundle.config.pluginRemapArgs

    override val paramMappings: MavenDep?
        get() = null

    override val decompiler: MavenDep?
        get() = null

    override val remapper: MavenDep
        get() = bundle.config.pluginRemapper

    override val mache: MavenDep
        get() = bundle.config.mache

    override val libraryRepositories: List<String>
        get() = bundle.config.libraryRepositories

    private fun createExtractFromBundlerStep(): ExtractFromBundlerStep = ExtractFromBundlerStep(
        cache,
        vanillaSteps,
        vanillaServerJar,
        minecraftLibraryJars,
        ::minecraftLibraryJars
    )
}
