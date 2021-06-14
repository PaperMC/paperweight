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

import io.papermc.paperweight.util.McDev
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import kotlin.io.path.listDirectoryEntries
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

abstract class ImportMcDev : DefaultTask() {

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val libraryImports: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val mcdevImports: RegularFileProperty

    @get:OutputDirectory
    abstract val paperServerDir: DirectoryProperty

    @TaskAction
    fun run() {
        val patches = patchDir.path.listDirectoryEntries("*.patch")
        val sourceDir = paperServerDir.path.resolve("src/main/java")
        McDev.importMcDev(patches, sourceMcDevJar.path, libraryImports.pathOrNull, mcLibrariesDir.path, mcdevImports.pathOrNull, sourceDir)
    }
}
