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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class ZippedTask : BaseTask() {

    @get:Optional
    @get:Classpath
    abstract val inputZip: RegularFileProperty

    @get:OutputFile
    abstract val outputZip: RegularFileProperty

    abstract fun run(rootDir: Path)

    override fun init() {
        outputZip.convention(defaultOutput("zip"))
    }

    @TaskAction
    fun exec() {
        val outputZipFile = outputZip.path
        val outputDir = findOutputDir(outputZipFile)

        try {
            val input = inputZip.pathOrNull
            if (input != null) {
                unzip(input, outputDir)
            } else {
                outputDir.createDirectories()
            }

            run(outputDir)

            ensureDeleted(outputZipFile)

            zip(outputDir, outputZipFile)
        } finally {
            outputDir.deleteRecursive()
        }
    }
}
