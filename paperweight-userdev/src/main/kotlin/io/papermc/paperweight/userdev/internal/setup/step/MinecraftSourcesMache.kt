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

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.LoggingOutputStream
import codechicken.diffpatch.util.archiver.ArchiveFormat
import io.papermc.paperweight.tasks.mache.macheDecompileJar
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.LogLevel

class MinecraftSourcesMache(
    @Input private val inputJar: Path,
    @Output private val outputJar: Path,
    private val cache: Path,
    private val minecraftLibraryJars: () -> List<Path>,
    @Input private val decompileArgs: List<String>,
    private val decompiler: Configuration,
    private val mache: Configuration,
) : SetupStep {
    override val name: String = "decompile and setup sources with mache"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        // Decompile
        val tempOut = outputJar.resolveSibling("${outputJar.name}.tmp")
        macheDecompileJar(
            tempOut,
            minecraftLibraryJars(),
            decompileArgs,
            inputJar,
            context.defaultJavaLauncher,
            decompiler,
            cache,
        )

        // Apply mache patches
        outputJar.ensureClean()
        val result = PatchOperation.builder()
            .logTo(LoggingOutputStream(context.project.logger, LogLevel.LIFECYCLE))
            .basePath(tempOut, ArchiveFormat.ZIP)
            .outputPath(outputJar, ArchiveFormat.ZIP)
            .patchesPath(mache.singleFile.toPath(), ArchiveFormat.ZIP)
            .patchesPrefix("patches")
            .level(codechicken.diffpatch.util.LogLevel.INFO)
            .build()
            .operate()
        tempOut.ensureClean()

        if (result.exit != 0) {
            throw Exception("Failed to apply ${result.summary.failedMatches} mache patches")
        }
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(minecraftLibraryJars())
        builder.include(decompiler.map { it.toPath() })
        builder.include(mache.map { it.toPath() })
        builder.includePaperweightHash = false
    }

    companion object {
        fun create(
            context: SetupHandler.Context,
            inputJar: Path,
            outputJar: Path,
            cache: Path,
            minecraftLibraryJars: () -> List<Path>,
            decompileArgs: List<String>,
        ): MinecraftSourcesMache {
            // resolve dependencies
            val decompiler = context.project.configurations.getByName(MACHE_DECOMPILER_CONFIG).also { it.resolve() }
            val mache = context.project.configurations.getByName(MACHE_CONFIG).also { it.resolve() }
            return MinecraftSourcesMache(inputJar, outputJar, cache, minecraftLibraryJars, decompileArgs, decompiler, mache)
        }
    }
}
