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

package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
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

        println("mapping to $DEOBF_NAMESPACE")

        merc.rewrite(inputDir, outputDir)

        cleanup()
    }

    private fun setup() {
        outputDir.deleteRecursively()
        outputDir.createDirectories()
    }

    private fun cleanup() {
        inputDir.deleteRecursively()
        outputDir.moveTo(inputDir)
        outputDir.deleteRecursively()
    }
}
