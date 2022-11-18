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
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class CollectATsFromPatches : BaseTask() {

    @get:Input
    abstract val header: Property<String>

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        header.convention("== AT ==")
        outputFile.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        outputFile.path.deleteForcefully()
        val patches = patchDir.path.listDirectoryEntries("*.patch")
        outputFile.path.writeLines(readAts(patches))
    }

    private fun readAts(patches: Iterable<Path>) : Set<String> {
        val result = hashSetOf<String>()

        val start = header.get()
        val end = "diff --git a/"
        for (patch in patches) {
            patch.useLines {
                var reading = false
                for (line in it) {
                    if (line.startsWith(end)) {
                        break
                    }
                    if (reading && line.isNotBlank()) {
                        result.add(line)
                    }
                    if (line.startsWith(start)) {
                        reading = true
                    }
                }
            }
        }
        return result
    }
}
