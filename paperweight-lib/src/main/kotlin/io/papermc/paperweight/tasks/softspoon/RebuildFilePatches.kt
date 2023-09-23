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

import atFromString
import codechicken.diffpatch.cli.DiffOperation
import codechicken.diffpatch.util.LogLevel
import codechicken.diffpatch.util.LoggingOutputStream
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.openrewrite.InMemoryExecutionContext
import writeLF

@UntrackedTask(because = "Always rebuild patches")
abstract class RebuildFilePatches : BaseTask() {

    @get:Input
    @get:Option(
        option = "verbose",
        description = "Prints out more info about the patching process",
    )
    abstract val verbose: Property<Boolean>

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:InputDirectory
    abstract val base: DirectoryProperty

    @get:OutputDirectory
    abstract val patches: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val atFile: RegularFileProperty

    @get:Optional
    @get:OutputFile
    abstract val atFileOut: RegularFileProperty

    @get:Optional
    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Input
    abstract val contextLines: Property<Int>

    override fun init() {
        contextLines.convention(3)
        verbose.convention(false)
    }

    @TaskAction
    fun run() {
        val patchDir = patches.convertToPath().ensureClean()
        patchDir.createDirectory()
        val inputDir = input.convertToPath()
        val baseDir = base.convertToPath()

        val oldAts = if (atFile.isPresent) AccessTransformFormats.FML.read(atFile.convertToPath()) else AccessTransformSet.create()

        val git = Git(inputDir)
        git("stash", "push").executeSilently(silenceErr = true)
        git("checkout", "file").executeSilently(silenceErr = true)

        // handle AT
        baseDir.walk()
            .map { it.relativeTo(baseDir).toString().replace("\\", "/") }
            .filter {
                !it.startsWith(".git") && !it.endsWith(".nbt") && !it.endsWith(".mcassetsroot")
            }
            .forEach {
                val ats = AccessTransformSet.create()
                val source = inputDir.resolve(it)
                val decomp = baseDir.resolve(it)
                val className = it.replace(".java", "")
                handleATInSource(source, ats, className)
                handleATInBase(decomp, ats, baseDir)
                oldAts.merge(ats)
            }

        if (atFileOut.isPresent) {
            AccessTransformFormats.FML.writeLF(
                atFileOut.convertToPath(),
                oldAts,
                "# This file is auto generated, any changes may be overridden!\n# See CONTRIBUTING.md on how to add access transformers.\n"
            )
        }

        // rebuild patches
        val printStream = PrintStream(LoggingOutputStream(logger, org.gradle.api.logging.LogLevel.LIFECYCLE))
        val result = DiffOperation.builder()
            .logTo(printStream)
            .aPath(baseDir)
            .bPath(inputDir)
            .outputPath(patchDir)
            .autoHeader(true)
            .level(if (verbose.get()) LogLevel.ALL else LogLevel.INFO)
            .lineEnding("\n")
            .ignorePrefix(".git")
            .ignorePrefix("data/minecraft/structures")
            .ignorePrefix("data/.mc")
            .ignorePrefix("assets/.mc")
            .context(contextLines.get())
            .summary(verbose.get())
            .build()
            .operate()

        git("switch", "-").executeSilently(silenceErr = true)
        git("stash", "pop").runSilently(silenceErr = true)

        logger.lifecycle("Rebuilt ${result.summary.changedFiles} patches")
    }

    private fun handleATInBase(decomp: Path, newAts: AccessTransformSet, decompRoot: Path) {
        if (newAts.classes.isEmpty()) {
            return
        }

        val configuration = RestampContextConfiguration.builder()
            .accessTransformSet(newAts)
            .sourceRoot(decompRoot)
            .sourceFiles(listOf(decomp))
            .classpath(minecraftClasspath.files.map { it.toPath() })
            .executionContext(InMemoryExecutionContext { it.printStackTrace() })
            .build()

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

    private fun handleATInSource(source: Path, newAts: AccessTransformSet, className: String) {
        val sourceLines = source.readLines()
        val fixedLines = ArrayList<String>(sourceLines.size)
        var requiresWrite = false
        sourceLines.forEach { line ->
            if (!line.contains("// Paper-AT: ")) {
                fixedLines.add(line)
                return@forEach
            }

            requiresWrite = true

            val split = line.split("// Paper-AT: ")
            val at = split[1]
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

            fixedLines.add(split[0])
        }

        if (requiresWrite) {
            source.writeText(fixedLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}
