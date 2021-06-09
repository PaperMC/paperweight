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
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.findOutputDir
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import io.papermc.paperweight.util.unzip
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
        printOutput.convention(false)
    }

    @TaskAction
    fun run() {
        Git(upstream.path).let { git ->
            git("fetch").setupOut().run()
            git("branch", "-f", upstreamBranch.get(), branch.get()).runSilently()
        }

        if (!outputDir.path.exists() || !outputDir.path.resolve(".git").exists()) {
            outputDir.path.deleteRecursively()
            Git(outputDir.path.parent)("clone", upstream.path.absolutePathString(), outputDir.path.name).setupOut().run()
        }

        val target = outputDir.path.name

        if (printOutput.get()) {
            println("   Resetting $target to ${upstream.path.name}...")
        }

        val rootPatchDir = patchDir.pathOrNull ?: patchZip.path.let { unzip(it, findOutputDir(it)) }

        try {
            Git(outputDir.path).let { git ->
                // disable gpg for this repo, not needed & slows things down
                git("config", "commit.gpgsign", "false").executeSilently()

                git("remote", "rm", "upstream").runSilently(silenceErr = true)
                git("remote", "add", "upstream", upstream.path.absolutePathString()).runSilently(silenceErr = true)
                if (git("checkout", "master").setupOut(showError = false).run() != 0) {
                    git("checkout", "-b", "master").setupOut().run()
                }
                git("fetch", "upstream").runSilently(silenceErr = true)
                git("reset", "--hard", "upstream/${upstreamBranch.get()}").setupOut().run()

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
    patchDir: Path,
    printOutput: Boolean
) {
    if (printOutput) {
        println("   Applying patches to $target...")
    }

    val statusFile = outputDir.resolve(".git/patch-apply-failed")
    statusFile.deleteForcefully()

    git("am", "--abort").runSilently(silenceErr = true)

    val patches = patchDir.useDirectoryEntries("*.patch") { it.toMutableList() }
    if (patches.isEmpty()) {
        if (printOutput) {
            println("No patches found")
        }
        return
    }

    patches.sort()

    if (git("am", "--3way", "--ignore-whitespace", *patches.map { it.absolutePathString() }.toTypedArray()).showErrors().run() != 0) {
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
}
