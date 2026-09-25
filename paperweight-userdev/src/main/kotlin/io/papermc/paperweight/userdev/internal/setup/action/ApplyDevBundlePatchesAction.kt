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

package io.papermc.paperweight.userdev.internal.setup.action

import io.codechicken.diffpatch.cli.PatchOperation
import io.codechicken.diffpatch.util.Input as DiffInput
import io.codechicken.diffpatch.util.LogLevel
import io.codechicken.diffpatch.util.Output as DiffOutput
import io.codechicken.diffpatch.util.archiver.ArchiveFormat
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.StringValue
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.siblingLogFile
import io.papermc.paperweight.util.*
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.streams.asSequence

class ApplyDevBundlePatchesAction(
    @Input private val decompiledJar: FileValue,
    @Input val bundleZip: FileValue,
    @Input val patchesPath: StringValue,
    @Output val outputJar: FileValue,
    @Input private val patchedJar: FileValue? = null,
) : WorkDispatcher.Action {
    override fun execute() {
        val tempPatchDir = findOutputDir(outputJar.get())
        val outputDir = findOutputDir(outputJar.get())
        val log = outputJar.get().siblingLogFile()

        try {
            bundleZip.get().openZipSafe().use { fs ->
                val patchesDir = fs.getPath(patchesPath.get())
                val (patches, newFiles) = Files.walk(patchesDir).use { stream ->
                    stream.asSequence()
                        .filter { it.isRegularFile() }
                        .partition { it.name.endsWith(".patch") }
                }
                for (patch in patches) {
                    relativeCopy(patchesDir, patch, tempPatchDir)
                }

                ensureDeleted(log)
                PrintStream(log.toFile(), Charsets.UTF_8).use { logOut ->
                    val op = PatchOperation.builder()
                        .logTo(logOut)
                        .level(LogLevel.ALL)
                        .summary(true)
                        .baseInput(DiffInput.MultiInput.archive(ArchiveFormat.ZIP, decompiledJar.get()))
                        .patchesInput(DiffInput.MultiInput.folder(tempPatchDir))
                        .patchedOutput(DiffOutput.MultiOutput.folder(outputDir))
                        .build()
                    try {
                        op.operate().throwOnError()
                    } catch (ex: Exception) {
                        throw PaperweightException(
                            "Failed to apply dev bundle patches. See the log file at '${log.toFile()}' for more details.",
                            ex
                        )
                    }
                }

                for (file in newFiles) {
                    relativeCopy(patchesDir, file, outputDir)
                }
            }

            ensureDeleted(outputJar.get())
            zip(outputDir, outputJar.get())

            // Merge classes and resources in
            patchedJar?.let { patched ->
                outputJar.get().openZip().use { fs ->
                    val out = fs.getPath("/")
                    patched.get().openZip().use { patchedFs ->
                        val patchedRoot = patchedFs.getPath("/")

                        patchedRoot.walk()
                            .filter { it.isRegularFile() }
                            .forEach { file ->
                                val copyTo = out.resolve(file.relativeTo(patchedRoot).invariantSeparatorsPathString)
                                copyTo.createParentDirectories()
                                file.copyTo(copyTo)
                            }
                    }
                }
            }
        } finally {
            ensureDeleted(outputDir, tempPatchDir)
        }
    }
}
