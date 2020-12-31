/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.commentRegex
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PatchMappings : DefaultTask() {

    @get:InputFile
    abstract val inputMappings: RegularFileProperty
    @get:InputFile
    @get:Optional
    abstract val patchMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty
    @get:OutputFile
    abstract val outputMappingsReversed: RegularFileProperty

    @TaskAction
    fun run() {
        val mappings = MappingFormats.TINY.read(
            inputMappings.path,
            Constants.SPIGOT_NAMESPACE,
            Constants.DEOBF_NAMESPACE
        )
        patchMappings.pathOrNull?.let { patchFile ->
            val temp = Files.createTempFile("patch", "tiny")
            try {
                val comment = commentRegex()
                // tiny format doesn't allow comments, so we manually remove them
                // The tiny mappings reader also doesn't have a InputStream or Reader input...
                Files.newBufferedReader(patchFile).useLines { lines ->
                    Files.newBufferedWriter(temp).use { writer ->
                        for (line in lines) {
                            val newLine = comment.replace(line, "")
                            if (newLine.isNotBlank()) {
                                writer.appendln(newLine)
                            }
                        }
                    }
                }
                MappingFormats.TINY.read(mappings, temp, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        MappingFormats.TINY.write(mappings, outputMappings.path, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)
        MappingFormats.TINY.write(mappings.reverse(), outputMappingsReversed.path, Constants.DEOBF_NAMESPACE, Constants.SPIGOT_NAMESPACE)
    }
}
