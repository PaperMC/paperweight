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

import io.papermc.paperweight.tasks.ServerBundler
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.util.constants.paperSetupOutput
import java.nio.file.Path

class ExtractFromBundlerStep(
    cache: Path,
    private val vanillaSteps: VanillaSteps,
    private val vanillaServerJar: Path,
    private val minecraftLibraryJars: Path,
    private val listMinecraftLibraryJars: () -> List<Path>,
) : SetupStep {
    override val name: String = "extract libraries and server from downloaded jar"

    override val hashFile: Path = cache.resolve(paperSetupOutput("extractFromServerBundler", "hashes"))

    override fun run(context: SetupHandler.ExecutionContext) {
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
