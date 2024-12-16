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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ImportLibraryFiles : BaseTask() {

    @get:Optional
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:Optional
    @get:InputFiles
    abstract val patches: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        super.init()
        outputDir.set(layout.cache.resolve(paperTaskOutput()))
    }

    @TaskAction
    fun run() {
        outputDir.path.deleteRecursive()
        outputDir.path.createDirectories()
        if (!libraries.isEmpty && !patches.isEmpty) {
            val patchFiles = patches.files.flatMap { it.toPath().walk().filter { path -> path.toString().endsWith(".patch") }.toList() }
            McDev.importMcDev(
                patchFiles,
                null,
                devImports.pathOrNull,
                outputDir.path,
                null,
                libraries.files.map { it.toPath() },
                true,
                ""
            )
        }
    }
}
