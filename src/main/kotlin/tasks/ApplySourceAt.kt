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
import io.papermc.paperweight.util.path
import java.io.File
import javax.inject.Inject
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

abstract class ApplySourceAt : ZippedTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty
    @get:InputFile
    abstract val vanillaRemappedSrgJar: RegularFileProperty
    @get:InputFile
    abstract val atFile: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun run(rootDir: File) {
        val input = rootDir.resolve("input")
        val output = rootDir.resolve("output")

        // Move everything into `input/` so we can put output into `output/`
        input.mkdirs()
        rootDir.listFiles()?.forEach { file ->
            if (file != input) {
                file.renameTo(input.resolve(file.name))
            }
        }
        output.mkdirs()

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx2G")
        }

        queue.submit(AtAction::class.java) {
            classpath.add(vanillaJar.file)
            classpath.add(vanillaRemappedSrgJar.file)

            at.set(atFile.file)

            inputDir.set(input)
            outputDir.set(output)
        }

        queue.await()

        // Remove input files
        rootDir.listFiles()?.forEach { file ->
            if (file != output) {
                file.deleteRecursively()
            }
        }

        // Move output into root
        output.listFiles()?.forEach { file ->
            file.renameTo(rootDir.resolve(file.name))
        }
        output.delete()
    }

    abstract class AtAction : WorkAction<AtParams> {
        override fun execute() {
            val at = AccessTransformFormats.FML.read(parameters.at.path)

            Mercury().let { merc ->
                merc.classPath.addAll(parameters.classpath.get().map { it.toPath() })

                merc.processors.add(AccessTransformerRewriter.create(at))

                merc.rewrite(parameters.inputDir.path, parameters.outputDir.path)
            }
        }
    }

    interface AtParams : WorkParameters {
        val classpath: ListProperty<File>
        val at: RegularFileProperty
        val inputDir: RegularFileProperty
        val outputDir: RegularFileProperty
    }
}
