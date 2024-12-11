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
import io.papermc.paperweight.userdev.internal.setup.util.siblingLogFile
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.file.FileCollection

class RemapMinecraft(
    @Input private val minecraftRemapArgs: List<String>,
    @Input private val filteredVanillaJar: Path,
    private val minecraftLibraryJars: () -> List<Path>,
    @Input private val mappings: Path,
    private val remapper: FileCollection,
    @Output private val outputJar: Path,
    private val cache: Path,
) : SetupStep {
    override val name: String = "remap minecraft server jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.ExecutionContext) {
        TinyRemapper.run(
            argsList = minecraftRemapArgs,
            logFile = outputJar.siblingLogFile(),
            inputJar = filteredVanillaJar,
            mappingsFile = mappings,
            fromNamespace = OBF_NAMESPACE,
            toNamespace = DEOBF_NAMESPACE,
            remapClasspath = minecraftLibraryJars(),
            remapper = remapper,
            outputJar = outputJar,
            launcher = context.javaLauncher,
            workingDir = cache
        )
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.includePaperweightHash = false
        builder.include(minecraftLibraryJars())
        builder.include(remapper.map { it.toPath() })
    }

    companion object {
        fun create(
            context: SetupHandler.ExecutionContext,
            minecraftRemapArgs: List<String>,
            filteredVanillaJar: Path,
            minecraftLibraryJars: () -> List<Path>,
            mappings: Path,
            outputJar: Path,
            cache: Path,
        ): RemapMinecraft {
            val remapper = context.remapperConfig.also { it.files.size } // resolve remapper
            return RemapMinecraft(
                minecraftRemapArgs,
                filteredVanillaJar,
                minecraftLibraryJars,
                mappings,
                remapper,
                outputJar,
                cache,
            )
        }
    }
}
