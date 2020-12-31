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

package io.papermc.paperweight.util

import java.nio.file.Files
import java.nio.file.Path
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.gradle.api.file.RegularFileProperty
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

class AtlasHelper private constructor(private val workerExecutor: WorkerExecutor) {

    fun remap(mappings: MappingSet, inputJar: Any): Path {
        val (outputJar, _) = runAtlas(mappings, inputJar)
        return outputJar
    }

    fun fillInheritance(mappings: MappingSet, inputJar: Any): MappingSet {
        val (outputJar, outputMappings) = runAtlas(mappings, inputJar)
        try {
            return outputMappings
        } finally {
            Files.deleteIfExists(outputJar)
        }
    }

    private fun runAtlas(mappings: MappingSet, input: Any): AtlasOutput {
        val inputFile = input.convertToFile()

        val tempMappingsFile = Files.createTempFile("in-mappings", "tiny")
        val tempOutputMappingsFile = Files.createTempFile("out-mappings", "tiny")
        val outputJarFile = Files.createTempFile("output", "jar")

        MappingFormats.TINY.write(mappings, tempMappingsFile, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)

        val resultMappings: MappingSet = try {
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs("-Xmx1G")
            }

            queue.submit(AtlasRunner::class) {
                inputJar.set(inputFile)
                outputJar.set(outputJarFile.toFile())
                mappingsFile.set(tempMappingsFile.toFile())
                outputMappingsFile.set(tempOutputMappingsFile.toFile())
            }

            queue.await()

            MappingFormats.TINY.read(tempOutputMappingsFile, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)
        } finally {
            Files.deleteIfExists(tempMappingsFile)
            Files.deleteIfExists(tempOutputMappingsFile)
        }

        return AtlasOutput(outputJarFile, resultMappings)
    }

    private data class AtlasOutput(
        val outputJar: Path,
        val outputMappings: MappingSet
    )

    companion object {
        fun using(workerExecutor: WorkerExecutor) = AtlasHelper(workerExecutor)
    }

    abstract class AtlasRunner : WorkAction<AtlasParameters> {
        override fun execute() {
            val mappings = MappingFormats.TINY.read(parameters.mappingsFile.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)

            Atlas().let { atlas ->
                atlas.install { ctx -> JarEntryRemappingTransformer(LorenzRemapper(mappings, ctx.inheritanceProvider())) }
                atlas.run(parameters.inputJar.path, parameters.outputJar.path)
            }

            MappingFormats.TINY.write(mappings, parameters.outputMappingsFile.path, Constants.OBF_NAMESPACE, Constants.DEOBF_NAMESPACE)
        }
    }

    interface AtlasParameters : WorkParameters {
        val inputJar: RegularFileProperty
        val outputJar: RegularFileProperty
        val mappingsFile: RegularFileProperty
        val outputMappingsFile: RegularFileProperty
    }
}
