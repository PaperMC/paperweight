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
abstract class MergeFiles : BaseTask() {

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val file: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val secondFile: RegularFileProperty

    @get:OutputFile
    abstract val mergedFile: RegularFileProperty

    @get:Input
    abstract val fileExt: Property<String>

    override fun init() {
        mergedFile.convention(defaultOutput(fileExt.get()))
    }

    @TaskAction
    fun run() {
        file.pathOrNull?.let { patchFile ->
            val comment = commentRegex()
            patchFile.useLines { lines ->
                mergedFile.path.bufferedWriter().use { writer ->
                    for (line in lines) {
                        val newLine = comment.replace(line, "")
                        if (newLine.isNotBlank()) {
                            writer.appendLine(newLine)
                        }
                    }
                    writer.appendLine("")
                }
            }
        }
        secondFile.pathOrNull?.let { patchFile ->
            val comment = commentRegex()
            patchFile.useLines { lines ->
                mergedFile.path.bufferedWriter().use { writer ->
                    for (line in lines) {
                        val newLine = comment.replace(line, "")
                        if (newLine.isNotBlank()) {
                            writer.appendLine(newLine)
                        }
                    }
                }
            }
        }
    }
}
