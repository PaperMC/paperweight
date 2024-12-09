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
import io.papermc.paperweight.util.deleteRecursive
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import org.gradle.api.file.FileCollection

class RemapMinecraftMache(
    @Input private val minecraftRemapArgs: List<String>,
    @Input private val vanillaJar: Path,
    private val minecraftLibraryJars: () -> List<Path>,
    @Input private val mappings: Path,
    @Input private val paramMappings: Path,
    @Input private val constants: Path?,
    private val codebook: FileCollection,
    private val remapper: FileCollection,
    @Output private val outputJar: Path,
    private val cache: Path,
) : SetupStep {
    override val name: String = "remap minecraft server jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.ExecutionContext) {
        val temp = createTempDirectory(cache, "remap")
        macheRemapJar(
            context.javaLauncher,
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
            context: SetupHandler.ExecutionContext,
            minecraftRemapArgs: List<String>,
            vanillaJar: Path,
            minecraftLibraryJars: () -> List<Path>,
            mappings: Path,
            outputJar: Path,
            cache: Path,
        ): RemapMinecraftMache {
            // resolve dependencies
            val remapper = context.macheRemapperConfig.also { it.files.size }
            val paramMappings = context.macheParamMappingsConfig.singleFile.toPath()
            val constants = context.macheConstantsConfig.files.singleOrNull()?.toPath()
            val codebook = context.macheCodebookConfig.also { it.files.size }
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
