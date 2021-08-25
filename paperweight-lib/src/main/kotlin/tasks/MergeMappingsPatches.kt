/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class MergeMappingsPatches : BaseTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputPatches: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patch: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    @get:OutputFile
    abstract val mergedPatches: RegularFileProperty

    override fun init() {
        mergedPatches.convention(defaultOutput("tiny"))
    }

    @TaskAction
    fun run() {
        mergePatches(inputPatches, mergedPatches)
    }

    private fun mergePatches(input: RegularFileProperty, output: RegularFileProperty) {
        input.pathOrNull?.let { patchFile ->
            val temp = createTempFile("inputMappingsPatches", "tiny")
            try {
                val comment = commentRegex()
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
                val mappings = MappingFormats.TINY.read(
                    temp,
                    fromNamespace.get(),
                    toNamespace.get()
                )

                patch.pathOrNull?.let { patchFileOther ->
                    val tempOther = createTempFile("mappingsPatchPatches", "tiny")
                    try {
                        val comments = commentRegex()
                        patchFileOther.useLines { lines ->
                            tempOther.bufferedWriter().use { writer ->
                                for (line in lines) {
                                    val newLine = comments.replace(line, "")
                                    if (newLine.isNotBlank()) {
                                        writer.appendLine(newLine)
                                    }
                                }
                            }
                        }
                        MappingFormats.TINY.read(mappings, tempOther, fromNamespace.get(), toNamespace.get())
                        MappingFormats.TINY.write(mappings, output.path, fromNamespace.get(), toNamespace.get())
                    } finally {
                        tempOther.deleteForcefully()
                    }
                }
            } finally {
                temp.deleteForcefully()
            }
        }
    }
}
