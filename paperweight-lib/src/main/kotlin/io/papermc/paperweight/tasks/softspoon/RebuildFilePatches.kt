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

import codechicken.diffpatch.cli.DiffOperation
import codechicken.diffpatch.util.LogLevel
import codechicken.diffpatch.util.LoggingOutputStream
import io.papermc.paperweight.PaperweightException
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
import org.intellij.lang.annotations.Language
import org.openrewrite.InMemoryExecutionContext

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

    @get:Optional
    @get:Input
    abstract val gitFilePatches: Property<Boolean>

    override fun init() {
        contextLines.convention(3)
        verbose.convention(false)
        gitFilePatches.convention(false)
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
        val filesWithNewAts = mutableListOf<String>()
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
                if (handleATInSource(source, ats, className)) {
                    handleATInBase(decomp, ats, baseDir)
                    filesWithNewAts.add(it)
                }
                oldAts.merge(ats)
            }

        if (atFileOut.isPresent) {
            AccessTransformFormats.FML.writeLF(
                atFileOut.convertToPath(),
                oldAts,
                "# This file is auto generated, any changes may be overridden!\n# See CONTRIBUTING.md on how to add access transformers.\n"
            )
        }

        if (filesWithNewAts.isNotEmpty()) {
            git("status").executeOut()
            git("diff").executeOut()
            // we removed the comment, we need to commit this
            git("add", ".").executeOut()
            git("commit", "--amend", "--no-edit").executeOut()
        }

        // rebuild patches
        val result = if (gitFilePatches.get()) {
            rebuildWithGit(git, patchDir)
        } else {
            rebuildWithDiffPatch(baseDir, inputDir, patchDir)
        }

        git("switch", "-").executeSilently(silenceErr = true)
        if (filesWithNewAts.isNotEmpty()) {
            try {
                // we need to rebase, so that the new file commit is part of the tree again.
                // for that we use GIT_SEQUENCE_EDITOR to drop the first commit
                // and then execs for all the files that remove the papter
                // todo detect if sed is not present (windows) and switch out sed for something else
                @Language("Shell Script")
                val sequenceEditor = "sed -i -e 0,/pick/{s/pick/drop/}"
                val execs = filesWithNewAts
                    .map { "sed -i -e 's|// Paper-AT:.*||g' $it && ((git add $it && git commit --amend --no-edit) || true)" }
                    .flatMap { listOf("--exec", it) }.toTypedArray()
                git.withEnv(
                    mapOf("GIT_SEQUENCE_EDITOR" to sequenceEditor)
                )("rebase", "-i", "file", "--strategy-option=theirs", *execs).executeSilently()
            } catch (e: Exception) {
                // TODO better message to inform the user on what to do
                throw RuntimeException("Encountered conflicts while rebuilding file patches.", e)
            }
        }
        git("stash", "pop").runSilently(silenceErr = true)

        val patchDirGit = Git(patchDir)
        patchDirGit("add", "-A", ".").executeSilently()

        logger.lifecycle("Rebuilt $result patches")
    }

    private fun rebuildWithGit(
        git: Git,
        patchDir: Path
    ): Int {
        val files = git("diff-tree", "--name-only", "--no-commit-id", "-r", "HEAD").getText().split("\n")
        files.parallelStream().forEach { filename ->
            if (filename.isBlank()) return@forEach
            val patch = git(
                "format-patch",
                "--diff-algorithm=myers",
                "--full-index",
                "--no-signature",
                "--no-stat",
                "--no-numbered",
                "-1",
                "HEAD",
                "--stdout",
                filename
            ).getText()
            val patchFile = patchDir.resolve("$filename.patch")
            patchFile.createParentDirectories()
            patchFile.writeText(patch)
        }

        return files.size
    }

    private fun rebuildWithDiffPatch(
        baseDir: Path,
        inputDir: Path,
        patchDir: Path
    ): Int {
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
        return result.summary.changedFiles
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
