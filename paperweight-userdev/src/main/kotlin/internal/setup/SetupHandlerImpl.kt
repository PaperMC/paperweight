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

package io.papermc.paperweight.userdev.internal.setup

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.step.*
import io.papermc.paperweight.userdev.internal.setup.util.buildHashFunction
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.repositories.IvyArtifactRepository

class SetupHandlerImpl(
    private val service: UserdevSetup,
    private val bundle: ExtractedBundle<GenerateDevBundle.DevBundleConfig>,
    private val cache: Path = service.parameters.cache.path,
) : SetupHandler {
    private val vanillaSteps = VanillaSteps(
        bundle.config.minecraftVersion,
        cache,
        service.parameters.downloadService.get(),
        bundle.changed,
    )

    private val vanillaServerJar: Path = cache.resolve(paperSetupOutput("vanillaServerJar", "jar"))
    private val minecraftLibraryJars = cache.resolve(MINECRAFT_JARS_PATH)
    private fun extractFromServerBundler() {
        vanillaSteps.downloadVanillaServerJar()

        val hashFunction = buildHashFunction(vanillaSteps.mojangJar, vanillaServerJar) {
            include(minecraftLibraryJars.filesMatchingRecursive("*.jar"))
        }
        val hashFile = cache.resolve(paperSetupOutput("extractFromServerBundler", "hashes"))
        if (hashFunction.upToDate(hashFile)) {
            return
        }

        UserdevSetup.LOGGER.lifecycle(":extracting libraries and server from downloaded jar")
        ServerBundler.extractFromBundler(
            vanillaSteps.mojangJar,
            vanillaServerJar,
            minecraftLibraryJars,
            null,
            null,
            null,
            null,
        )
        hashFunction.writeHash(hashFile)
    }

    private val filteredVanillaServerJar: Path = cache.resolve(paperSetupOutput("filterJar", "jar"))
    private fun filterVanillaServerJar() {
        extractFromServerBundler()
        filterVanillaServerJar(
            vanillaServerJar,
            filteredVanillaServerJar,
            bundle.config.buildData.vanillaJarIncludes
        )
    }

    private val mojangPlusYarnMappings: Path = cache.resolve(MOJANG_YARN_MAPPINGS)
    private fun generateMappings(context: SetupHandler.Context) {
        filterVanillaServerJar()
        generateMappings(
            context,
            vanillaSteps,
            filteredVanillaServerJar,
            { minecraftLibraryJars.filesMatchingRecursive("*.jar") },
            mojangPlusYarnMappings,
        )
    }

    private val mappedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("mappedMinecraftServerJar", "jar"))
    private fun remapMinecraftServerJar(context: SetupHandler.Context) {
        generateMappings(context)

        remapMinecraftServerJar(
            context,
            bundle.config.buildData.minecraftRemapArgs,
            filteredVanillaServerJar,
            { minecraftLibraryJars.filesMatchingRecursive("*.jar") },
            mojangPlusYarnMappings,
            mappedMinecraftServerJar,
            cache,
        )
    }

    private val fixedMinecraftServerJar: Path = cache.resolve(paperSetupOutput("fixedMinecraftServerJar", "jar"))
    private fun fixMinecraftServerJar(context: SetupHandler.Context) {
        remapMinecraftServerJar(context)

        fixMinecraftServerJar(
            context,
            mappedMinecraftServerJar,
            fixedMinecraftServerJar,
            vanillaServerJar,
        )
    }

    private val accessTransformedServerJar: Path = cache.resolve(paperSetupOutput("accessTransformedServerJar", "jar"))
    private fun accessTransformMinecraftServerJar(context: SetupHandler.Context) {
        fixMinecraftServerJar(context)

        accessTransformMinecraftServerJar(
            context,
            bundle.dir.resolve(bundle.config.buildData.accessTransformFile),
            fixedMinecraftServerJar,
            accessTransformedServerJar,
        )
    }

    private val decompiledMinecraftServerJar: Path = cache.resolve(paperSetupOutput("decompileMinecraftServerJar", "jar"))
    private fun decompileMinecraftServerJar(context: SetupHandler.Context) {
        accessTransformMinecraftServerJar(context)

        decompileMinecraftServerJar(
            context,
            accessTransformedServerJar,
            decompiledMinecraftServerJar,
            cache,
            { minecraftLibraryJars.filesMatchingRecursive("*.jar") },
            bundle.config.decompile.args,
        )
    }

    private val patchedSourcesJar: Path = cache.resolve(paperSetupOutput("patchedSourcesJar", "jar"))
    private fun patchDecompiledSources(context: SetupHandler.Context) {
        decompileMinecraftServerJar(context)

        applyDevBundlePatches(
            decompiledMinecraftServerJar,
            bundle.dir.resolve(bundle.config.patchDir),
            patchedSourcesJar
        )
    }

    private val mojangMappedPaperJar: Path = cache.resolve(paperSetupOutput("applyMojangMappedPaperclipPatch", "jar"))

    // This can be called when a user queries the server jar provider in
    // PaperweightUserExtension, possibly by a task running in a separate
    // thread to dependency resolution.
    @Synchronized
    private fun applyMojangMappedPaperclipPatch(context: SetupHandler.Context) {
        if (setupCompleted) {
            return
        }

        patchPaperclip(
            context = context,
            paperclip = bundle.dir.resolve(bundle.config.buildData.mojangMappedPaperclipFile),
            outputJar = mojangMappedPaperJar,
            mojangJar = vanillaSteps.mojangJar,
            minecraftVersion = minecraftVersion,
        )
    }

    private var setupCompleted = false

    @Synchronized
    override fun createOrUpdateIvyRepository(context: SetupHandler.Context) {
        if (setupCompleted) {
            return
        }

        patchDecompiledSources(context)
        applyMojangMappedPaperclipPatch(context)

        val deps = bundle.config.buildData.compileDependencies.toList() +
            bundle.config.apiCoordinates +
            bundle.config.mojangApiCoordinates
        installPaperServer(
            cache,
            bundle.config.mappedServerCoordinates,
            deps,
            patchedSourcesJar,
            mojangMappedPaperJar,
        )

        setupCompleted = true
    }

    override fun configureIvyRepo(repo: IvyArtifactRepository) {
        repo.content {
            includeFromDependencyNotation(bundle.config.mappedServerCoordinates)
        }
    }

    override fun populateCompileConfiguration(context: SetupHandler.Context, dependencySet: DependencySet) {
        dependencySet.add(context.project.dependencies.create(bundle.config.mappedServerCoordinates))
    }

    override fun populateRuntimeConfiguration(context: SetupHandler.Context, dependencySet: DependencySet) {
        listOf(
            bundle.config.mappedServerCoordinates,
            bundle.config.apiCoordinates,
            bundle.config.mojangApiCoordinates
        ).forEach { coordinate ->
            val dep = context.project.dependencies.create(coordinate).also {
                (it as ExternalModuleDependency).isTransitive = false
            }
            dependencySet.add(dep)
        }

        for (coordinates in bundle.config.buildData.runtimeDependencies) {
            val dep = context.project.dependencies.create(coordinates).also {
                (it as ExternalModuleDependency).isTransitive = false
            }
            dependencySet.add(dep)
        }
    }

    override fun serverJar(context: SetupHandler.Context): Path {
        applyMojangMappedPaperclipPatch(context)
        return mojangMappedPaperJar
    }

    override val serverJar: Path
        get() = mojangMappedPaperJar

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

    override val libraryRepositories: List<String>
        get() = bundle.config.buildData.libraryRepositories
}
