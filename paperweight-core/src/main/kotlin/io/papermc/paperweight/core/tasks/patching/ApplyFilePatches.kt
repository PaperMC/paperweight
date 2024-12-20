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

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.match.FuzzyLineMatcher
import codechicken.diffpatch.util.LoggingOutputStream
import codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option

abstract class ApplyFilePatches : BaseTask() {

    @get:Input
    @get:Option(
        option = "verbose",
        description = "Prints out more info about the patching process",
    )
    abstract val verbose: Property<Boolean>

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    @get:Optional
    abstract val patches: DirectoryProperty

    @get:Internal
    abstract val rejects: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val gitFilePatches: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val baseRef: Property<String>

    @get:Input
    @get:Optional
    abstract val identifier: Property<String>

    init {
        run {
            verbose.convention(false)
            gitFilePatches.convention(false)
        }
    }

    @TaskAction
    open fun run() {
        io.papermc.paperweight.util.Git.checkForGit()

        val outputPath = output.path
        recreateCloneDirectory(outputPath)

        checkoutRepoFromUpstream(
            Git(outputPath),
            input.path,
            baseRef.getOrElse("main"),
            "upstream",
            "main",
            baseRef.isPresent,
        )

        setupGitHook(outputPath)

        val result = if (!patches.isPresent) {
            commit()
            0
        } else if (gitFilePatches.get()) {
            applyWithGit(outputPath)
        } else {
            applyWithDiffPatch()
        }

        if (!verbose.get()) {
            logger.lifecycle("Applied $result patches")
        }
    }

    private fun applyWithGit(outputPath: Path): Int {
        val git = Git(outputPath)
        val patches = patches.path.filesMatchingRecursive("*.patch")
        val patchStrings = patches.map { outputPath.relativize(it).pathString }
        patchStrings.chunked(12).forEach {
            git("apply", "--3way", *it.toTypedArray()).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
        }

        commit()

        return patches.size
    }

    private fun applyWithDiffPatch(): Int {
        val printStream = PrintStream(LoggingOutputStream(logger, LogLevel.LIFECYCLE))
        val builder = PatchOperation.builder()
            .logTo(printStream)
            .basePath(output.path)
            .patchesPath(patches.path)
            .outputPath(output.path)
            .level(if (verbose.get()) codechicken.diffpatch.util.LogLevel.ALL else codechicken.diffpatch.util.LogLevel.INFO)
            .mode(mode())
            .minFuzz(minFuzz())
            .summary(verbose.get())
            .lineEnding("\n")
            .ignorePrefix(".git")
        if (rejects.isPresent) {
            builder.rejectsPath(rejects.path)
        }

        val result = builder.build().operate()

        commit()

        if (result.exit != 0) {
            val total = result.summary.failedMatches + result.summary.exactMatches +
                result.summary.accessMatches + result.summary.offsetMatches + result.summary.fuzzyMatches
            throw Exception("Failed to apply ${result.summary.failedMatches}/$total hunks")
        }

        return result.summary.changedFiles
    }

    private fun setupGitHook(outputPath: Path) {
        val hook = outputPath.resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
        hook.toFile().setExecutable(true)
    }

    private fun commit() {
        val ident = PersonIdent(PersonIdent("File", "noreply+automated@papermc.io"), Instant.parse("1997-04-20T13:37:42.69Z"))
        val git = Git.open(output.path.toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("${identifier.get()} File Patches")
            .setAuthor(ident)
            .setAllowEmpty(true)
            .setSign(false)
            .call()
        git.tagDelete().setTags("file").call()
        git.tag().setName("file").setTagger(ident).setSigned(false).call()
        git.close()
    }

    internal open fun mode(): PatchMode {
        return PatchMode.OFFSET
    }

    internal open fun minFuzz(): Float {
        return FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE
    }
}
