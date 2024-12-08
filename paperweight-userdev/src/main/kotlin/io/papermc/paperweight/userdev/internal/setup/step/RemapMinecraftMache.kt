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

import io.papermc.paperweight.tasks.mache.macheRemapJar
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.artifacts.Configuration

class RemapMinecraftMache(
    @Input private val minecraftRemapArgs: List<String>,
    @Input private val vanillaJar: Path,
    private val minecraftLibraryJars: () -> List<Path>,
    @Input private val mappings: Path,
    @Input private val paramMappings: Path,
    @Input private val constants: Path?,
    private val codebook: Configuration,
    private val remapper: Configuration,
    @Output private val outputJar: Path,
    private val cache: Path,
) : SetupStep {
    override val name: String = "remap minecraft server jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        val temp = createTempDirectory(cache, "remap")
        macheRemapJar(
            context.defaultJavaLauncher,
            codebook,
            outputJar,
            minecraftRemapArgs,
            temp,
            remapper,
            mappings,
            paramMappings,
            constants,
            vanillaJar,
            minecraftLibraryJars()
        )
        temp.deleteRecursive()
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.includePaperweightHash = false
        builder.include(minecraftLibraryJars())
        builder.include(remapper.map { it.toPath() })
        builder.include(codebook.map { it.toPath() })
    }

    companion object {
        fun create(
            context: SetupHandler.Context,
            minecraftRemapArgs: List<String>,
            vanillaJar: Path,
            minecraftLibraryJars: () -> List<Path>,
            mappings: Path,
            outputJar: Path,
            cache: Path,
        ): RemapMinecraftMache {
            // resolve dependencies
            val remapper = context.project.configurations.getByName(MACHE_REMAPPER_CONFIG).also { it.resolve() }
            val paramMappings = context.project.configurations.getByName(MACHE_PARAM_MAPPINGS_CONFIG).singleFile.toPath()
            val constants = context.project.configurations.getByName(MACHE_CONSTANTS_CONFIG).files.singleOrNull()?.toPath()
            val codebook = context.project.configurations.getByName(MACHE_CODEBOOK_CONFIG).also { it.resolve() }
            return RemapMinecraftMache(
                minecraftRemapArgs,
                vanillaJar,
                minecraftLibraryJars,
                mappings,
                paramMappings,
                constants,
                codebook,
                remapper,
                outputJar,
                cache,
            )
        }
    }
}
