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

package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.util.Constants
import java.nio.file.Files
import java.nio.file.Path
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.remapper.MercuryRemapper

class PatchSourceRemapWorker(
    mappings: MappingSet,
    ats: AccessTransformSet,
    classpath: Collection<Path>,
    private val inputDir: Path,
    private val outputDir: Path
) {

    private val merc: Mercury = Mercury()

    init {
        merc.classPath.addAll(classpath)

        merc.processors.addAll(
            listOf(
                MercuryRemapper.create(mappings),
                AccessTransformerRewriter.create(ats)
            )
        )

        merc.isGracefulClasspathChecks = true
    }

    fun remap() {
        setup()

        println("mapping to ${Constants.DEOBF_NAMESPACE}")

        merc.rewrite(inputDir, outputDir)

        cleanup()
    }

    private fun setup() {
        outputDir.deleteRecursively()
        Files.createDirectories(outputDir)
    }

    private fun cleanup() {
        inputDir.deleteRecursively()
        Files.move(outputDir, inputDir)
        outputDir.deleteRecursively()
    }

    private fun Path.deleteRecursively() {
        if (Files.notExists(this)) {
            return
        }
        Files.walk(this).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
