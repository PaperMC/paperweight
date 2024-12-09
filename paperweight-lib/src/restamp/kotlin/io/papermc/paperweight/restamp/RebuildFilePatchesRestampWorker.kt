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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.path
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.openrewrite.InMemoryExecutionContext

abstract class RebuildFilePatchesRestampWorker : WorkAction<RebuildFilePatchesRestampWorker.Params> {
    companion object {
        private val logger: Logger = Logging.getLogger(RebuildFilePatchesRestampWorker::class.java)
    }

    interface Params : WorkParameters {
        val baseDir: DirectoryProperty
        val inputDir: DirectoryProperty
        val atFile: RegularFileProperty
        val atFileOut: RegularFileProperty
        val minecraftClasspath: ConfigurableFileCollection
        val filesWithNewAts: RegularFileProperty
    }

    override fun execute() {
        val oldAts = if (parameters.atFile.isPresent) {
            AccessTransformFormats.FML.read(parameters.atFile.path)
        } else {
            AccessTransformSet.create()
        }

        // handle AT
        val filesWithNewAts = mutableListOf<String>()
        parameters.baseDir.path.walk()
            .map { it.relativeTo(parameters.baseDir.path).toString().replace("\\", "/") }
            .filter {
                !it.startsWith(".git") && !it.endsWith(".nbt") && !it.endsWith(".mcassetsroot")
            }
            .forEach {
                val ats = AccessTransformSet.create()
                val source = parameters.inputDir.path.resolve(it)
                val decomp = parameters.baseDir.path.resolve(it)
                val className = it.replace(".java", "")
                if (handleATInSource(source, ats, className)) {
                    handleATInBase(decomp, ats, parameters.baseDir.path)
                    filesWithNewAts.add(it)
                }
                oldAts.merge(ats)
            }

        if (parameters.atFileOut.isPresent) {
            AccessTransformFormats.FML.writeLF(
                parameters.atFileOut.path,
                oldAts,
                "# This file is auto generated, any changes may be overridden!\n# See CONTRIBUTING.md on how to add access transformers.\n"
            )
        }

        parameters.filesWithNewAts.path.writeLines(filesWithNewAts)
    }

    private fun handleATInBase(decomp: Path, newAts: AccessTransformSet, decompRoot: Path) {
        if (newAts.classes.isEmpty()) {
            return
        }

        val configuration = RestampContextConfiguration.builder()
            .accessTransformSet(newAts)
            .sourceRoot(decompRoot)
            .sourceFiles(listOf(decomp))
            .classpath(parameters.minecraftClasspath.files.map { it.toPath() })
            .executionContext(InMemoryExecutionContext { it.printStackTrace() })
            .build()

        // mmmh, maybe add comment to base too?

        val parsedInput = RestampInput.parseFrom(configuration)
        val results = Restamp.run(parsedInput).allResults

        if (results.size != 1) {
            logger.lifecycle("Failed to apply AT to ${decomp.fileName} (doesn't it already exist?): $results")
            return
        }

        val result = results[0].after?.printAll()
        if (result != null) {
            decomp.writeText(result, Charsets.UTF_8)
        }
    }

    private fun handleATInSource(source: Path, newAts: AccessTransformSet, className: String): Boolean {
        val sourceLines = source.readLines()
        var foundNew = false
        val fixedLines = ArrayList<String>(sourceLines.size)
        sourceLines.forEach { line ->
            if (!line.contains("// Paper-AT: ")) {
                fixedLines.add(line)
                return@forEach
            }

            foundNew = true

            val split = line.split("// Paper-AT: ")
            val at = split[1]
            try {
                val atClass = newAts.getOrCreateClass(className)
                val parts = at.split(" ")
                val accessTransform = atFromString(parts[0])
                val name = parts[1]
                val index = name.indexOf('(')
                if (index == -1) {
                    atClass.mergeField(name, accessTransform)
                } else {
                    atClass.mergeMethod(MethodSignature.of(name.substring(0, index), name.substring(index)), accessTransform)
                }
                logger.lifecycle("Found new AT in $className: $at -> $accessTransform")
            } catch (ex: Exception) {
                throw PaperweightException("Found invalid AT '$at' in class $className")
            }

            fixedLines.add(split[0])
        }

        if (foundNew) {
            source.writeText(fixedLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }

        return foundNew
    }
}
