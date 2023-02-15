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

package io.papermc.paperweight.userdev.internal.setup.step

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path

class GenerateMappingsStep(
    private val vanillaSteps: VanillaSteps,
    @Input private val filteredVanillaJar: Path,
    @Input private val paramMappings: Path,
    private val minecraftLibraryJars: () -> List<Path>,
    @Output private val outputMappings: Path,
) : SetupStep {
    override val name: String = "generate mappings"

    override val hashFile: Path = outputMappings.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        generateMappings(
            vanillaJarPath = filteredVanillaJar,
            libraryPaths = minecraftLibraryJars(),
            vanillaMappingsPath = vanillaSteps.serverMappings,
            paramMappingsPath = paramMappings,
            outputMappingsPath = outputMappings,
            workerExecutor = context.workerExecutor,
            launcher = context.defaultJavaLauncher
        ).await()
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(minecraftLibraryJars())
        builder.include(vanillaSteps.serverMappings)
    }

    companion object {
        fun create(
            context: SetupHandler.Context,
            vanillaSteps: VanillaSteps,
            filteredVanillaJar: Path,
            minecraftLibraryJars: () -> List<Path>,
            outputMappings: Path,
        ): GenerateMappingsStep {
            vanillaSteps.downloadServerMappings()

            // resolve param mappings
            val paramMappings = context.project.configurations.named(PARAM_MAPPINGS_CONFIG).map { it.singleFile }.convertToPath()

            return GenerateMappingsStep(vanillaSteps, filteredVanillaJar, paramMappings, minecraftLibraryJars, outputMappings)
        }
    }
}
