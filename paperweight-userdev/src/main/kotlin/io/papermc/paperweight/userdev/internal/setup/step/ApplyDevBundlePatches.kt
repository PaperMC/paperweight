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
import codechicken.diffpatch.util.archiver.ArchiveFormat
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.hashDirectory
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.userdev.internal.setup.util.siblingLogFile
import io.papermc.paperweight.util.*
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.asSequence

class ApplyDevBundlePatches(
    @Input private val decompiledJar: Path,
    private val devBundlePatches: Path,
    @Output private val outputJar: Path,
) : SetupStep {
    override val name: String = "apply patches to decompiled jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        val tempPatchDir = findOutputDir(outputJar)
        val outputDir = findOutputDir(outputJar)
        val log = outputJar.siblingLogFile()

        try {
            val (patches, newFiles) = Files.walk(devBundlePatches).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() }
                    .partition { it.name.endsWith(".patch") }
            }
            for (patch in patches) {
                relativeCopy(devBundlePatches, patch, tempPatchDir)
            }

            ensureDeleted(log)
            PrintStream(log.toFile(), Charsets.UTF_8).use { logOut ->
                val op = PatchOperation.builder()
                    .logTo(logOut)
                    .verbose(true)
                    .summary(true)
                    .basePath(decompiledJar, ArchiveFormat.ZIP)
                    .patchesPath(tempPatchDir)
                    .outputPath(outputDir)
                    .build()
                try {
                    op.operate().throwOnError()
                } catch (ex: Exception) {
                    throw PaperweightException(
                        "Failed to apply dev bundle patches. See the log file at '${log.toFile()}' for more details. " +
                            "Usually, the issue is with the dev bundle itself, and not the userdev project.",
                        ex
                    )
                }
            }

            for (file in newFiles) {
                relativeCopy(devBundlePatches, file, outputDir)
            }

            ensureDeleted(outputJar)
            zip(outputDir, outputJar)
        } finally {
            ensureDeleted(outputDir, tempPatchDir)
        }
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(hashDirectory(devBundlePatches))
    }
}
