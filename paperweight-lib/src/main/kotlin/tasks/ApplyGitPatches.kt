/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem

abstract class ApplyGitPatches : ControllableOutputTask() {

    @get:Input
    abstract val branch: Property<String>

    @get:Input
    abstract val upstreamBranch: Property<String>

    @get:InputDirectory
    abstract val upstream: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val patchZip: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        outputs.upToDateWhen { false }
        printOutput.convention(false)
    }

    @TaskAction
    fun run() {
        Git(upstream.path).let { git ->
            git("fetch").setupOut().run()
            git("branch", "-f", upstreamBranch.get(), branch.get()).runSilently()
        }

        val outputPath = outputDir.path
        recreateCloneDirectory(outputPath)

        val target = outputPath.name

        if (printOutput.get()) {
            println("   Resetting $target to ${upstream.path.name}...")
        }

        val rootPatchDir = patchDir.pathOrNull ?: patchZip.path.let { unzip(it, findOutputDir(it)) }

        try {
            Git(outputPath).let { git ->
                checkoutRepoFromUpstream(git, upstream.path, upstreamBranch.get())
                applyGitPatches(git, target, outputDir.path, rootPatchDir, printOutput.get())
            }
        } finally {
            if (rootPatchDir != patchDir.pathOrNull) {
                rootPatchDir.deleteRecursively()
            }
        }
    }
}

fun ControllableOutputTask.applyGitPatches(
    git: Git,
    target: String,
    outputDir: Path,
    patchDir: Path?,
    printOutput: Boolean
) {
    if (printOutput) {
        println("   Applying patches to $target...")
    }

    val statusFile = outputDir.resolve(".git/patch-apply-failed")
    statusFile.deleteForcefully()

    git("am", "--abort").runSilently(silenceErr = true)

    val patches = patchDir?.useDirectoryEntries("*.patch") { it.toMutableList() } ?: mutableListOf()
    if (patches.isEmpty()) {
        if (printOutput) {
            println("No patches found")
        }
        return
    }

    // This prevents the `git am` command line from getting too big with too many patches
    // mostly an issue with Windows
    val tempDir = createTempDirectory(layout.cache, "paperweight")
    try {
        val mailDir = tempDir.resolve("new")
        mailDir.createDirectories()

        for (patch in patches) {
            patch.copyTo(mailDir.resolve(patch.fileName))
        }

        if (git("am", "--3way", "--ignore-whitespace", tempDir.absolutePathString()).showErrors().run() != 0) {
            statusFile.writeText("1")
            logger.error("***   Please review above details and finish the apply then")
            logger.error("***   save the changes with `./gradlew rebuildPaperPatches`")

            if (OperatingSystem.current().isWindows) {
                logger.error("")
                logger.error("***   Because you're on Windows you'll need to finish the AM,")
                logger.error("***   rebuild all patches, and then re-run the patch apply again.")
                logger.error("***   Consider using the scripts with Windows Subsystem for Linux.")
            }

            throw PaperweightException("Failed to apply patches")
        } else {
            statusFile.deleteForcefully()
            if (printOutput) {
                println("   Patches applied cleanly to $target")
            }
        }
    } finally {
        tempDir.deleteRecursively()
    }
}

fun checkoutRepoFromUpstream(git: Git, upstream: Path, upstreamBranch: String) {
    git("init", "--quiet").executeSilently()
    git("config", "commit.gpgsign", "false").executeSilently()
    git("remote", "remove", "upstream").runSilently(silenceErr = true)
    git("remote", "add", "upstream", upstream.toUri().toString()).executeSilently(silenceErr = true)
    git("fetch", "upstream", "--prune").executeSilently(silenceErr = true)
    if (git("checkout", "master").runSilently(silenceErr = true) != 0) {
        git("checkout", "-b", "master").runSilently(silenceErr = true)
    }
    git("reset", "--hard", "upstream/$upstreamBranch").executeSilently(silenceErr = true)
    git("gc").runSilently(silenceErr = true)
}

fun recreateCloneDirectory(target: Path) {
    if (target.exists()) {
        if (target.resolve(".git").isDirectory()) {
            val git = Git(target)
            git("clean", "-fxd").runSilently(silenceErr = true)
            git("reset", "--hard", "HEAD").runSilently(silenceErr = true)
        } else {
            for (entry in target.listDirectoryEntries()) {
                entry.deleteRecursively()
            }
            target.createDirectories()
        }
    } else {
        target.createDirectories()
    }
}
