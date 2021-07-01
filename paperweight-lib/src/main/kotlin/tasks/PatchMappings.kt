/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.commentRegex
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PatchMappings : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputMappings: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patch: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @TaskAction
    fun run() {
        appendPatch(inputMappings, outputMappings)
    }

    private fun appendPatch(input: RegularFileProperty, output: RegularFileProperty) {
        val mappings = MappingFormats.TINY.read(
            input.path,
            fromNamespace.get(),
            toNamespace.get()
        )
        patch.pathOrNull?.let { patchFile ->
            val temp = createTempFile("patch", "tiny")
            try {
                val comment = commentRegex()
                // tiny format doesn't allow comments, so we manually remove them
                // The tiny mappings reader also doesn't have a InputStream or Reader input...
                patchFile.useLines { lines ->
                    temp.bufferedWriter().use { writer ->
                        for (line in lines) {
                            val newLine = comment.replace(line, "")
                            if (newLine.isNotBlank()) {
                                writer.appendLine(newLine)
                            }
                        }
                    }
                }
                MappingFormats.TINY.read(mappings, temp, fromNamespace.get(), toNamespace.get())
            } finally {
                temp.deleteForcefully()
            }
        }

        MappingFormats.TINY.write(mappings, output.path, fromNamespace.get(), toNamespace.get())
    }
}
