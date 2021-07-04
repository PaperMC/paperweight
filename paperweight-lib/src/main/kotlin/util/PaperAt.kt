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

package io.papermc.paperweight.util

import java.nio.file.Path
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

object PaperAt {

    fun apply(workerExecutor: WorkerExecutor, apiDir: Path, serverDir: Path, atFile: Path?) {
        if (atFile == null) {
            return
        }

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx2G")
        }

        val srcDir = serverDir.resolve("src/main/java")

        // Remap sources
        queue.submit(AtAction::class) {
            classpath.from(apiDir.resolve("src/main/java"))

            inputDir.set(srcDir)
            outputDir.set(srcDir)
            ats.set(atFile)
        }

        queue.await()
    }

    interface AtParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val inputDir: DirectoryProperty
        val outputDir: DirectoryProperty
        val ats: RegularFileProperty
    }

    abstract class AtAction : WorkAction<AtParams> {
        override fun execute() {
            Mercury().let { merc ->
                merc.classPath.addAll(parameters.classpath.map { it.toPath() })
                merc.isGracefulClasspathChecks = true

                merc.process(parameters.inputDir.path)

                merc.processors.clear()
                merc.processors.addAll(
                    listOf(
                        AccessTransformerRewriter.create(AccessTransformFormats.FML.read(parameters.ats.path))
                    )
                )

                merc.rewrite(parameters.inputDir.path, parameters.outputDir.path)
            }
        }
    }
}
