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
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet
import org.gradle.kotlin.dsl.*

class SetupHandlerImplV2(
    private val parameters: UserdevSetup.Parameters,
    private val bundle: ExtractedBundle<DevBundleV2.Config>,
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
    private val filteredVanillaServerJar: Path = cache.resolve(paperSetupOutput("filterJar", "jar"))
    private val minecraftLibraryJars = cache.resolve(MINECRAFT_JARS_PATH)
    private val mojangPlusYarnMappings: Path = cache.resolve(MOJANG_YARN_MAPPINGS)
    private val mappedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("mappedMinecraftServerJar", "jar"))
    private val fixedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("fixedMinecraftServerJar", "jar"))
    private val accessTransformedServerJar: Path = cache.resolve(paperSetupOutput("accessTransformedServerJar", "jar"))
    private val decompiledMinecraftServerJar: Path = cache.resolve(paperSetupOutput("decompileMinecraftServerJar", "jar"))
    private val patchedSourcesJar: Path = cache.resolve(paperSetupOutput("patchedSourcesJar", "jar"))
    private val mojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("applyMojangMappedPaperclipPatch", "jar"))

    private fun minecraftLibraryJars(): List<Path> = minecraftLibraryJars.listDirectoryEntries("*.jar")

    private fun generateSources(context: SetupHandler.ExecutionContext) {
        vanillaSteps.downloadVanillaServerJar()

        val downloadMcLibs = object : SetupStep {
            override val name: String = "download minecraft libraries"

            override val hashFile: Path = cache.resolve(paperSetupOutput("minecraftLibraries", "hashes"))

            override fun run(context: SetupHandler.ExecutionContext) {
                downloadLibraries(
                    download = parameters.downloadService,
                    workerExecutor = context.workerExecutor,
                    targetDir = minecraftLibraryJars,
                    repositories = listOf(MC_LIBRARY_URL, MAVEN_CENTRAL_URL),
                    libraries = bundle.config.buildData.vanillaServerLibraries,
                    sources = false
                ).await()
            }

            override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
                builder.include(MC_LIBRARY_URL)
                builder.include(bundle.config.buildData.vanillaServerLibraries)
                builder.include(minecraftLibraryJars())
                builder.includePaperweightHash = false
            }
        }

        val filterVanillaJarStep = FilterVanillaJar(vanillaSteps.mojangJar, bundle.config.buildData.vanillaJarIncludes, filteredVanillaServerJar)

        val genMappingsStep = GenerateMappingsStep.create(
            context,
            vanillaSteps,
            filteredVanillaServerJar,
            ::minecraftLibraryJars,
            mojangPlusYarnMappings,
        )

        val remapMinecraftStep = RemapMinecraft.create(
            context,
            bundle.config.remap.args,
            filteredVanillaServerJar,
            ::minecraftLibraryJars,
            mojangPlusYarnMappings,
            mappedMinecraftServerJar,
            cache,
        )

        val fixStep = FixMinecraftJar(
            mappedMinecraftServerJar,
            fixedMinecraftServerJar,
            vanillaSteps.mojangJar,
            true,
        )

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
            patchedSourcesJar
        )

        StepExecutor.executeSteps(
            bundle.changed,
            context,
            downloadMcLibs,
            filterVanillaJarStep,
            genMappingsStep,
            remapMinecraftStep,
            fixStep,
            atStep,
            decomp,
            applyDevBundlePatchesStep,
        )

        applyMojangMappedPaperclipPatch(context)

        StepExecutor.executeStep(
            context,
            FilterPaperShadowJar(
                patchedSourcesJar,
                mojangMappedPaperJar,
                filteredMojangMappedPaperJar,
                bundle.config.buildData.relocations,
            )
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
                    false,
                )
            )
        }
    }

    private val filteredMojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("filteredMojangMappedPaperJar", "jar"))

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

    private var setupCompleted = false

    @Synchronized
    override fun combinedOrClassesJar(context: SetupHandler.ExecutionContext): Path {
        if (setupCompleted) {
            return filteredMojangMappedPaperJar
        }

        lockSetup(cache) {
            generateSources(context)
        }

        setupCompleted = true

        return filteredMojangMappedPaperJar
    }

    override fun afterEvaluate(project: Project) {
        super.afterEvaluate(project)
        project.tasks.withType(UserdevSetupTask::class).configureEach {
            mappedServerJar.set(filteredMojangMappedPaperJar)
            legacyPaperclipResult.set(mojangMappedPaperJar)
        }
    }

    override val reobfMappings: Path
        get() = bundle.dir.resolve(bundle.config.buildData.reobfMappingsFile)

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
