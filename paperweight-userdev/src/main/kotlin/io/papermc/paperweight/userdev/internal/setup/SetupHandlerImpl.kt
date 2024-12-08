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
import io.papermc.paperweight.userdev.internal.setup.step.MinecraftSourcesMache
import io.papermc.paperweight.userdev.internal.setup.step.RemapMinecraftMache
import io.papermc.paperweight.userdev.internal.setup.util.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Path
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
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

    private fun generateSources(context: SetupHandler.Context) {
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
    private fun applyMojangMappedPaperclipPatch(context: SetupHandler.Context) {
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

    private var setupCompleted = false

    @Synchronized
    override fun createOrUpdateIvyRepository(context: SetupHandler.Context) {
        if (setupCompleted) {
            return
        }

        lockSetup(cache) {
            createOrUpdateIvyRepositoryDirect(context)
        }
    }

    private fun createOrUpdateIvyRepositoryDirect(context: SetupHandler.Context) {
        val source = if (parameters.genSources.get()) {
            generateSources(context)
            patchedSourcesJar
        } else {
            vanillaSteps.downloadVanillaServerJar()
            StepExecutor.executeStep(context, createExtractFromBundlerStep())
            applyMojangMappedPaperclipPatch(context)
            null
        }

        installPaperServer(
            cache,
            mappedServerCoordinates(),
            determineArtifactCoordinates(context.project.configurations.getByName(DEV_BUNDLE_CONFIG)),
            mojangMappedPaperJar,
            source,
            minecraftVersion,
        )

        setupCompleted = true
    }

    private fun mappedServerCoordinates(): String =
        "io.papermc.paperweight:dev-bundle-server:$minecraftVersion"

    override fun configureIvyRepo(repo: IvyArtifactRepository) {
        repo.content {
            includeFromDependencyNotation(mappedServerCoordinates())
        }
    }

    override fun populateCompileConfiguration(context: SetupHandler.Context, dependencySet: DependencySet) {
        dependencySet.add(context.project.dependencies.create(mappedServerCoordinates()))
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.Context, dependencySet: DependencySet) {
        dependencySet.add(context.project.dependencies.create(mappedServerCoordinates()))
    }

    override fun serverJar(context: SetupHandler.Context): Path {
        applyMojangMappedPaperclipPatch(context)
        return mojangMappedPaperJar
    }

    private fun macheMeta(): MacheMeta = requireNotNull(macheMeta) { "Mache meta is not setup yet" }

    @Synchronized
    override fun afterEvaluate(context: SetupHandler.Context) {
        val project = context.project
        val configurations = project.configurations
        if (macheMeta == null) {
            macheMeta = configurations.resolveMacheMeta()

            configurations.register(MACHE_CODEBOOK_CONFIG) {
                isTransitive = false
            }
            configurations.register(MACHE_REMAPPER_CONFIG) {
                isTransitive = false
            }
            configurations.register(MACHE_DECOMPILER_CONFIG) {
                isTransitive = false
            }
            configurations.register(MACHE_PARAM_MAPPINGS_CONFIG) {
                isTransitive = false
            }
            configurations.register(MACHE_CONSTANTS_CONFIG) {
                isTransitive = false
            }

            macheMeta().addDependencies(project)
            macheMeta().addRepositories(project)
        }
    }

    override val serverJar: Path
        get() = mojangMappedPaperJar

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

    private class ExtractFromBundlerStep(
        cache: Path,
        private val vanillaSteps: VanillaSteps,
        private val vanillaServerJar: Path,
        private val minecraftLibraryJars: Path,
        private val listMinecraftLibraryJars: () -> List<Path>,
    ) : SetupStep {
        override val name: String = "extract libraries and server from downloaded jar"

        override val hashFile: Path = cache.resolve(paperSetupOutput("extractFromServerBundler", "hashes"))

        override fun run(context: SetupHandler.Context) {
            ServerBundler.extractFromBundler(
                vanillaSteps.mojangJar,
                vanillaServerJar,
                minecraftLibraryJars,
                null,
                null,
                null,
                null,
            )
        }

        override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
            builder.include(vanillaSteps.mojangJar, vanillaServerJar)
            builder.include(listMinecraftLibraryJars())
        }
    }
}
