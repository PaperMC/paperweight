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

import codechicken.diffpatch.cli.DiffOperation
import codechicken.diffpatch.util.LogLevel
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.io.PrintStream
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class RebuildSingleFilePatches : BaseTask() {
    @get:Nested
    abstract val patches: ListProperty<Patch>

    abstract class Patch @Inject constructor(
        objects: ObjectFactory,
        upstream: DirectoryProperty,
    ) {
        companion object {
            fun patch(
                objects: ObjectFactory,
                upstream: DirectoryProperty,
                op: Action<Patch>
            ): Patch {
                val patch = objects.newInstance<Patch>(upstream)
                op.execute(patch)
                return patch
            }
        }

        @get:Input
        abstract val path: Property<String>

        @get:InputFile
        val upstreamFile: RegularFileProperty = objects.fileProperty().convention(upstream.file(path))

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
                patch.upstreamFile.path.copyTo(baseFile, true)

                val patchedFile = tmpB.resolve(patch.path.get()).createParentDirectories()
                patch.outputFile.path.copyTo(patchedFile, true)

                val result = DiffOperation.builder()
                    .logTo(logOut)
                    .aPath(tmpA)
                    .bPath(tmpB)
                    .outputPath(tmpPatch)
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
