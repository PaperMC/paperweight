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

package io.papermc.paperweight.core.tasks.patching

import io.codechicken.diffpatch.cli.DiffOperation
import io.codechicken.diffpatch.util.Input as DiffInput
import io.codechicken.diffpatch.util.LogLevel
import io.codechicken.diffpatch.util.Output as DiffOutput
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.io.PrintStream
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class RebuildSingleFilePatches : BaseTask() {

    @get:InputDirectory
    abstract val upstream: DirectoryProperty

    @get:Nested
    abstract val patches: ListProperty<Patch>

    abstract class Patch {
        @get:Input
        abstract val path: Property<String>

        @get:InputFile
        abstract val outputFile: RegularFileProperty

        @get:OutputFile
        abstract val patchFile: RegularFileProperty
    }

    @TaskAction
    fun run() {
        val tmpA = temporaryDir.resolve("a").toPath()
        val tmpB = temporaryDir.resolve("b").toPath()
        val tmpPatch = temporaryDir.resolve("patch").toPath()
        val log = temporaryDir.resolve("log.txt").toPath()

        ensureDeleted(log)
        PrintStream(log.toFile(), Charsets.UTF_8).use { logOut ->
            for (patch in patches.get()) {
                tmpA.deleteRecursive()
                tmpB.deleteRecursive()
                tmpPatch.deleteRecursive()

                val baseFile = tmpA.resolve(patch.path.get()).createParentDirectories()
                upstream.path.resolve(patch.path.get()).copyTo(baseFile, true)

                val patchedFile = tmpB.resolve(patch.path.get()).createParentDirectories()
                patch.outputFile.path.copyTo(patchedFile, true)

                val result = DiffOperation.builder()
                    .logTo(logOut)
                    .baseInput(DiffInput.MultiInput.folder(tmpA))
                    .changedInput(DiffInput.MultiInput.folder(tmpB))
                    .patchesOutput(DiffOutput.MultiOutput.folder(tmpPatch))
                    .autoHeader(true)
                    .level(LogLevel.ALL)
                    .lineEnding("\n")
                    .context(3)
                    .summary(true)
                    .build()
                    .operate()

                val patchOut = tmpPatch.resolve(patch.path.get() + ".patch")
                if (patchOut.exists()) {
                    patchOut.copyTo(patch.patchFile.path.createParentDirectories(), true)
                } else {
                    patch.patchFile.path.deleteIfExists()
                }
            }
        }
    }
}
