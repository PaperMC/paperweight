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
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Files
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.openrewrite.InMemoryExecutionContext

@CacheableTask
abstract class ApplySourceAT : BaseTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:Optional
    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val worker: WorkerExecutor

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val queue = worker.processIsolation {
            forkOptions {
                maxHeapSize = "2G"
            }
        }

        val classPath = minecraftClasspath.files.map { it.toPath() }.toMutableList()
        classPath.add(inputJar.convertToPath())

        queue.submit(RestampWorker::class) {
            minecraftClasspath.from(minecraftClasspath)
            atFile.set(atFile)
            inputJar.set(inputJar)
            outputJar.set(outputJar)
        }
    }
}

abstract class RestampWorker : WorkAction<RestampWorker.Params> {
    interface Params : WorkParameters {
        val minecraftClasspath: ConfigurableFileCollection
        val atFile: RegularFileProperty
        val inputJar: RegularFileProperty
        val outputJar: RegularFileProperty
    }

    override fun execute() {
        val inputZip = parameters.inputJar.convertToPath().openZip()

        val classPath = parameters.minecraftClasspath.files.map { it.toPath() }.toMutableList()
        classPath.add(parameters.inputJar.convertToPath())

        val configuration = RestampContextConfiguration.builder()
            .accessTransformers(parameters.atFile.convertToPath(), AccessTransformFormats.FML)
            .sourceRoot(inputZip.getPath("/"))
            .sourceFilesFromAccessTransformers()
            .classpath(classPath)
            .executionContext(InMemoryExecutionContext { it.printStackTrace() })
            .failWithNotApplicableAccessTransformers()
            .build()

        val parsedInput = RestampInput.parseFrom(configuration)
        val results = Restamp.run(parsedInput).allResults

        parameters.outputJar.convertToPath().writeZip().use { zip ->
            val alreadyWritten = mutableSetOf<String>()
            results.forEach { result ->
                if (result.after == null) {
                    println("Ignoring ${result.before?.sourcePath} because result.after is null?")
                    return@forEach
                }
                result.after?.let { after ->
                    zip.getPath(after.sourcePath.toString()).writeText(after.printAll())
                    alreadyWritten.add("/" + after.sourcePath.toString())
                }
            }

            inputZip.walk().filter { Files.isRegularFile(it) }.filter { !alreadyWritten.contains(it.toString()) }.forEach { file ->
                zip.getPath(file.toString()).writeText(file.readText())
            }

            zip.close()
        }
        inputZip.close()
    }
}
