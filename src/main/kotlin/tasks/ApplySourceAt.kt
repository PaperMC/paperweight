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

import io.papermc.paperweight.util.path
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import java.io.File

abstract class ApplySourceAt : ZippedTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty
    @get:InputFile
    abstract val vanillaRemappedSrgJar: RegularFileProperty
    @get:InputFile
    abstract val atFile: RegularFileProperty

    override fun run(rootDir: File) {
        val inputDir = rootDir.resolve("input")
        val outputDir = rootDir.resolve("output")

        // Move everything into `input/` so we can put output into `output/`
        inputDir.mkdirs()
        rootDir.listFiles()?.forEach { file ->
            if (file != inputDir) {
                file.renameTo(inputDir.resolve(file.name))
            }
        }
        outputDir.mkdirs()

        val at = AccessTransformFormats.FML.read(atFile.path)

        Mercury().apply {
            classPath.addAll(listOf(
                vanillaJar.path,
                vanillaRemappedSrgJar.path
            ))

            processors.add(AccessTransformerRewriter.create(at))

            rewrite(inputDir.toPath(), outputDir.toPath())
        }

        // Remove input files
        rootDir.listFiles()?.forEach { file ->
            if (file != outputDir) {
                file.deleteRecursively()
            }
        }

        // Move output into root
        outputDir.listFiles()?.forEach { file ->
            file.renameTo(rootDir.resolve(file.name))
        }
        outputDir.delete()
    }
}
