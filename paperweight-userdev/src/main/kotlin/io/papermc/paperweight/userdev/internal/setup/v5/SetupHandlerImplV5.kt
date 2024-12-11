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

import io.papermc.paperweight.userdev.internal.setup.ExtractedBundle
import io.papermc.paperweight.userdev.internal.setup.RunPaperclip
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.UserdevSetupTask
import io.papermc.paperweight.userdev.internal.setup.step.*
import io.papermc.paperweight.userdev.internal.setup.util.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.kotlin.dsl.*

class SetupHandlerImplV5(
    private val parameters: UserdevSetup.Parameters,
    private val bundle: ExtractedBundle<DevBundleV5.Config>,
    private val cache: Path = parameters.cache.path,
) : SetupHandler {
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
    private val filteredVanillaServerJar: Path = cache.resolve(paperSetupOutput("filterJar", "jar"))
    private val mojangPlusYarnMappings: Path = cache.resolve(MOJANG_YARN_MAPPINGS)
    private val mappedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("mappedMinecraftServerJar", "jar"))
    private val fixedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("fixedMinecraftServerJar", "jar"))
    private val accessTransformedServerJar: Path = cache.resolve(paperSetupOutput("accessTransformedServerJar", "jar"))
    private val decompiledMinecraftServerJar: Path = cache.resolve(paperSetupOutput("decompileMinecraftServerJar", "jar"))
    private val patchedSourcesJar: Path = cache.resolve(paperSetupOutput("patchedSourcesJar", "jar"))
    private val mojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("applyMojangMappedPaperclipPatch", "jar"))

    private fun minecraftLibraryJars(): List<Path> = minecraftLibraryJars.filesMatchingRecursive("*.jar")

    private fun generateSources(context: SetupHandler.ExecutionContext) {
        vanillaSteps.downloadVanillaServerJar()
        applyMojangMappedPaperclipPatch(context)

        val extractStep = createExtractFromBundlerStep()

        val filterVanillaJarStep = FilterVanillaJar(vanillaServerJar, bundle.config.buildData.vanillaJarIncludes, filteredVanillaServerJar)

        val genMappingsStep = GenerateMappingsStep.create(
            context,
            vanillaSteps,
            filteredVanillaServerJar,
            ::minecraftLibraryJars,
            mojangPlusYarnMappings,
        )

        val remapMinecraftStep = RemapMinecraft.create(
            context,
            bundle.config.buildData.minecraftRemapArgs,
            filteredVanillaServerJar,
            ::minecraftLibraryJars,
            mojangPlusYarnMappings,
            mappedMinecraftServerJar,
            cache,
        )

        val fixStep = FixMinecraftJar(mappedMinecraftServerJar, fixedMinecraftServerJar, vanillaServerJar)

        val atStep = AccessTransformMinecraft(
            bundle.dir.resolve(bundle.config.buildData.accessTransformFile),
            fixedMinecraftServerJar,
            accessTransformedServerJar,
        )

        val decomp = DecompileMinecraft.create(
            context,
            accessTransformedServerJar,
            decompiledMinecraftServerJar,
            cache,
            ::minecraftLibraryJars,
            bundle.config.decompile.args,
        )

        val applyDevBundlePatchesStep = ApplyDevBundlePatches(
            decompiledMinecraftServerJar,
            bundle.dir.resolve(bundle.config.patchDir),
            patchedSourcesJar,
            mojangMappedPaperJar
        )

        StepExecutor.executeSteps(
            bundle.changed,
            context,
            extractStep,
            filterVanillaJarStep,
            genMappingsStep,
            remapMinecraftStep,
            fixStep,
            atStep,
            decomp,
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
                    bundle.dir.resolve(bundle.config.buildData.mojangMappedPaperclipFile),
                    mojangMappedPaperJar,
                    vanillaSteps.mojangJar,
                    minecraftVersion,
                )
            )
        }
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

    override fun afterEvaluate(project: Project) {
        super.afterEvaluate(project)
        project.tasks.withType(UserdevSetupTask::class).configureEach {
            if (parameters.genSources.get()) {
                mappedServerJar.set(patchedSourcesJar)
            } else {
                mappedServerJar.set(mojangMappedPaperJar)
            }
        }
    }

    override val reobfMappings: Path
        get() = bundle.dir.resolve(bundle.config.buildData.reobfMappingsFile)

    override val minecraftVersion: String
        get() = bundle.config.minecraftVersion

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

    private fun createExtractFromBundlerStep(): ExtractFromBundlerStep = ExtractFromBundlerStep(
        cache,
        vanillaSteps,
        vanillaServerJar,
        minecraftLibraryJars,
        ::minecraftLibraryJars
    )
}
