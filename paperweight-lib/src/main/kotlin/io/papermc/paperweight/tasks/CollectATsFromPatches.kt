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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class CollectATsFromPatches : BaseTask() {
    companion object {
        private const val PATCH_CONTENT_START = "diff --git a/"
        private const val CO_AUTHOR_LINE = "Co-authored-by: "
    }

    @get:Input
    abstract val header: Property<String>

    @get:IgnoreEmptyDirectories
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val extraPatchDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        header.convention("== AT ==")
        outputFile.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        if (patchDir.isPresent.not() && extraPatchDir.isPresent.not()) {
            outputFile.path.writeText("")
            return
        }

        outputFile.path.deleteForcefully()
        val patches = patchDir.path.listDirectoryEntries("*.patch") +
            (extraPatchDir.pathOrNull?.listDirectoryEntries("*.patch") ?: emptyList())
        outputFile.path.writeLines(readAts(patches))
    }

    private fun readAts(patches: Iterable<Path>): List<String> {
        val result = hashSetOf<String>()

        val start = header.get()
        for (patch in patches) {
            patch.useLines {
                var reading = false
                for (line in it) {
                    if (line.startsWith(PATCH_CONTENT_START) || line.startsWith(CO_AUTHOR_LINE, true)) {
                        break
                    }
                    if (reading && line.isNotBlank() && !line.startsWith('#')) {
                        result.add(line)
                    }
                    if (line.startsWith(start)) {
                        reading = true
                    }
                }
            }
        }

        return result.sorted()
    }
}
