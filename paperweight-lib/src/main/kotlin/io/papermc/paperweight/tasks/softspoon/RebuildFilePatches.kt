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
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
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
import org.openrewrite.InMemoryExecutionContext
import writeLF

@UntrackedTask(because = "Always rebuild patches")
abstract class RebuildFilePatches : BaseTask() {

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

        val patchesCreated = baseDir.walk()
            .map { it.relativeTo(baseDir).toString().replace("\\", "/") }
            .filter {
                !it.startsWith(".git") && !it.endsWith(".nbt") && !it.endsWith(".mcassetsroot")
            }
            .sumOf {
                diffFile(inputDir, baseDir, it, patchDir, oldAts)
            }

        git("switch", "-").executeSilently(silenceErr = true)
        git("stash", "pop").runSilently(silenceErr = true)

        logger.lifecycle("Rebuilt $patchesCreated patches")
    }

    private fun diffFile(sourceRoot: Path, decompRoot: Path, relativePath: String, patchDir: Path, oldAts: AccessTransformSet): Int {
        val source = sourceRoot.resolve(relativePath)
        val decomp = decompRoot.resolve(relativePath)

        val className = relativePath.replace(".java", "")

        var sourceLines = source.readLines(Charsets.UTF_8)
        var decompLines = decomp.readLines(Charsets.UTF_8)

        val ats = AccessTransformSet.create()

        sourceLines = handleATInSource(sourceLines, ats, className, source)
        decompLines = handleATInBase(decompLines, ats, decompRoot, decomp)

        if (ats.classes.isNotEmpty()) {
            oldAts.merge(ats)
            AccessTransformFormats.FML.writeLF(atFileOut.convertToPath(), oldAts)
        }

        val patch = DiffUtils.diff(decompLines, sourceLines)
        if (patch.deltas.isEmpty()) {
            return 0
        }

        val unifiedPatch = UnifiedDiffUtils.generateUnifiedDiff(
            "a/$relativePath",
            "b/$relativePath",
            decompLines,
            patch,
            contextLines.get(),
        )

        val patchFile = patchDir.resolve("$relativePath.patch")
        patchFile.parent.createDirectories()
        patchFile.writeText(unifiedPatch.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        return 1
    }

    private fun handleATInBase(decompLines: List<String>, newAts: AccessTransformSet, decompRoot: Path, decomp: Path): List<String> {
        if (newAts.classes.isEmpty()) {
            return decompLines
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
            throw Exception("Strange resultset?! " + results)
        }

        val result = results[0].after?.printAll()
        if (result != null) {
            decomp.writeText(result, Charsets.UTF_8)
        }
        return decomp.readLines(Charsets.UTF_8)
    }

    private fun handleATInSource(sourceLines: List<String>, newAts: AccessTransformSet, className: String, source: Path): ArrayList<String> {
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

            fixedLines.add(split[0])
        }

        if (requiresWrite) {
            source.writeText(fixedLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }

        return fixedLines
    }
}
