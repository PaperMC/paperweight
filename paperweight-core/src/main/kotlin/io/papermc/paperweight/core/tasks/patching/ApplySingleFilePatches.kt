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

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.LogLevel
import codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.PaperweightException
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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.newInstance

abstract class ApplySingleFilePatches : BaseTask() {

    @get:Internal
    abstract val upstream: DirectoryProperty

    @get:Nested
    abstract val patches: ListProperty<Patch>

    @get:Input
    abstract val mode: Property<PatchMode>

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

        @get:Internal
        abstract val path: Property<String>

        @get:InputFile
        val upstreamFile: RegularFileProperty = objects.fileProperty().convention(upstream.file(path))

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @get:InputFile
        @get:Optional
        abstract val patchFile: RegularFileProperty
    }

    override fun init() {
        super.init()
        mode.convention(PatchMode.EXACT)
    }

    @TaskAction
    fun run() {
        val tmpWork = temporaryDir.resolve("work").toPath()
        val tmpPatch = temporaryDir.resolve("patch").toPath()
        val tmpRej = temporaryDir.resolve("rejects").toPath()
        tmpRej.deleteRecursive()
        val log = temporaryDir.resolve("log.txt").toPath()

        ensureDeleted(log)
        val error = PrintStream(log.toFile(), Charsets.UTF_8).use { logOut ->
            for (patch in patches.get()) {
                tmpWork.deleteRecursive()
                tmpPatch.deleteRecursive()

                val workFile = tmpWork.resolve(patch.path.get()).createParentDirectories()
                upstream.path.resolve(patch.path.get())
                    .copyTo(workFile, true)

                if (patch.patchFile.isPresent) {
                    patch.patchFile.path.copyTo(tmpPatch.resolve(patch.path.get() + ".patch").createParentDirectories(), true)

                    val op = PatchOperation.builder()
                        .logTo(logOut)
                        .level(LogLevel.ALL)
                        .mode(mode.get())
                        .summary(true)
                        .basePath(tmpWork)
                        .patchesPath(tmpPatch)
                        .outputPath(tmpWork)
                        .rejectsPath(tmpRej)
                        .build()

                    val result = op.operate()

                    workFile.copyTo(patch.outputFile.path.createParentDirectories(), true)

                    if (result.exit != 0) {
                        return@use "Patch failed on ${patch.patchFile.path}, see log above. Rejects at $tmpRej"
                    }
                } else {
                    upstream.path.resolve(patch.path.get())
                        .copyTo(patch.outputFile.path.createParentDirectories(), true)
                }
            }

            null
        }

        if (error != null) {
            logger.error(log.readText())
            throw PaperweightException(error)
        }
    }
}
