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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import net.fabricmc.lorenztiny.TinyMappingFormat
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class PatchMappings : BaseTask() {

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
        appendPatch(
            inputMappings.path,
            patch.pathOrNull,
            outputMappings.path
        )
    }

    private fun appendPatch(input: Path, patch: Path?, output: Path) {
        val mappings = MappingFormats.TINY.readCommented(
            input,
            fromNamespace.get(),
            toNamespace.get()
        )
        patch?.let {
            MappingFormats.TINY.readCommented(
                it,
                fromNamespace.get(),
                toNamespace.get(),
                mappings
            )
        }

        MappingFormats.TINY.write(mappings, output, fromNamespace.get(), toNamespace.get())
    }

    private fun TinyMappingFormat.readCommented(
        mappings: Path,
        fromNamespace: String,
        toNamespace: String,
        into: MappingSet? = null
    ): MappingSet {
        val temp = createTempFile("patch", "tiny")
        try {
            val comment = commentRegex()
            // tiny format doesn't allow comments, so we manually remove them
            // The tiny mappings reader also doesn't have a InputStream or Reader input...
            mappings.useLines { lines ->
                temp.bufferedWriter().use { writer ->
                    for (line in lines) {
                        val newLine = comment.replace(line, "")
                        if (newLine.isNotBlank()) {
                            writer.appendLine(newLine)
                        }
                    }
                }
            }
            return into?.let { read(it, temp, fromNamespace, toNamespace) }
                ?: read(temp, fromNamespace, toNamespace)
        } finally {
            temp.deleteForcefully()
        }
    }
}
