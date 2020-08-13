/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.util.file
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.mercury.Mercury
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

open class RemapPatches : DefaultTask() {

    @InputDirectory
    val inputPatchDir: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val sourceDir: DirectoryProperty = project.objects.directoryProperty()

    @InputFile
    val fromMappingsFile: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val toMappingsFile: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val vanillaJar: RegularFileProperty = project.objects.fileProperty()

    @OutputDirectory
    val outputPatchDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        val patches = inputPatchDir.file.listFiles() ?: return run {
            println("No input patches found")
        }

        patches.sort()

        val fromMappings = MappingFormats.TSRG.createReader(fromMappingsFile.file.toPath()).use { it.read() }
        val toMappings = MappingFormats.TSRG.createReader(toMappingsFile.file.toPath()).use { it.read() }


    }

    private fun remapToOldAndApplyPatch(patchFile: File) {

    }

    private fun remapToOld(source: File, fromMappings: MappingSet, vanillaJar: File) {
        Mercury().apply {
            classPath.addAll(vanillaJar.toPath())
        }
    }
}
