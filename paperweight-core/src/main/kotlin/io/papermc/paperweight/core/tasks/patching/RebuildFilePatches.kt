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

package io.papermc.paperweight.core.tasks.patching

import codechicken.diffpatch.cli.DiffOperation
import codechicken.diffpatch.util.LogLevel
import codechicken.diffpatch.util.LoggingOutputStream
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.*

@UntrackedTask(because = "Always rebuild patches")
abstract class RebuildFilePatches : JavaLauncherTask() {

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

    @get:Input
    abstract val contextLines: Property<Int>

    @get:Optional
    @get:Input
    abstract val gitFilePatches: Property<Boolean>

    override fun init() {
        super.init()
        contextLines.convention(3)
        verbose.convention(false)
        gitFilePatches.convention(false)
    }

    @TaskAction
    fun run() {
        val patchDir = patches.path.cleanDir()
        val inputDir = input.convertToPath()
        val baseDir = base.convertToPath()

        val git = Git(inputDir)
        git("stash", "push").executeSilently(silenceErr = true)
        git("checkout", MACHE_TAG_FILE).executeSilently(silenceErr = true)

        val result = if (gitFilePatches.get()) {
            rebuildWithGit(git, patchDir)
        } else {
            rebuildWithDiffPatch(baseDir, inputDir, patchDir)
        }

        git("switch", "-").executeSilently(silenceErr = true)
        git("stash", "pop").runSilently(silenceErr = true)

        val patchDirGit = Git(patchDir)
        patchDirGit("add", "-A", ".").executeSilently(silenceErr = true)

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
            .ignorePrefix("data/minecraft/structure")
            .ignorePrefix("data/.mc")
            .ignorePrefix("assets/.mc")
            .context(contextLines.get())
            .summary(verbose.get())
            .build()
            .operate()
        return result.summary.changedFiles
    }
}
