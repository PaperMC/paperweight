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

package io.papermc.paperweight.restamp

import io.papermc.paperweight.util.*
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.openrewrite.InMemoryExecutionContext

abstract class ApplySourceATWorker : WorkAction<ApplySourceATWorker.Params> {
    interface Params : WorkParameters {
        val minecraftClasspath: ConfigurableFileCollection
        val atFile: RegularFileProperty
        val inputJar: RegularFileProperty
        val outputJar: RegularFileProperty
    }

    override fun execute() {
        val inputZip = parameters.inputJar.path.openZip()

        val classPath = parameters.minecraftClasspath.files.map { it.toPath() }.toMutableList()
        classPath.add(parameters.inputJar.path)

        val configuration = RestampContextConfiguration.builder()
            .accessTransformers(parameters.atFile.path, AccessTransformFormats.FML)
            .sourceRoot(inputZip.getPath("/"))
            .sourceFilesFromAccessTransformers()
            .classpath(classPath)
            .executionContext(InMemoryExecutionContext { it.printStackTrace() })
            .failWithNotApplicableAccessTransformers()
            .build()

        val parsedInput = RestampInput.parseFrom(configuration)
        val results = Restamp.run(parsedInput).allResults

        parameters.outputJar.path.writeZip().use { zip ->
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
