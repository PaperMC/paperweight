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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.io.path.createDirectories
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class ApplyFeaturePatches : ControllableOutputTask() {

    @get:InputDirectory
    @get:Optional
    abstract val base: DirectoryProperty

    @get:OutputDirectory
    abstract val repo: DirectoryProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    @get:Optional
    abstract val patches: DirectoryProperty

    @get:Input
    abstract val verbose: Property<Boolean>

    override fun init() {
        printOutput.convention(false).finalizeValueOnRead()
        verbose.convention(false)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val base = base.pathOrNull
        if (base != null && base.toAbsolutePath() != repo.path.toAbsolutePath()) {
            val git = Git(repo.path.createDirectories())
            checkoutRepoFromUpstream(
                git,
                base,
                "file",
                branchName = "main",
                ref = true,
            )
        }

        if (!patches.isPresent) {
            return
        }

        val repoPath = repo.path

        val git = Git(repoPath)

        if (git("checkout", "main").runSilently(silenceErr = true) != 0) {
            git("checkout", "-b", "main").runSilently(silenceErr = true)
        }
        git("reset", "--hard", MACHE_TAG_FILE).executeSilently(silenceErr = true)
        git("gc").runSilently(silenceErr = true)

        applyGitPatches(git, "server repo", repoPath, patches.path, printOutput.get(), verbose.get())
    }

    private fun applyGitPatches(
        git: Git,
        target: String,
        outputDir: Path,
        patchDir: Path?,
        printOutput: Boolean,
        verbose: Boolean,
    ) {
        if (printOutput) {
            logger.lifecycle("Applying patches to $target...")
        }

        val statusFile = outputDir.resolve(".git/patch-apply-failed")
        statusFile.deleteForcefully()

        git("am", "--abort").runSilently(silenceErr = true)

        val patches = patchDir?.useDirectoryEntries("*.patch") { it.toMutableList() } ?: mutableListOf()
        if (patches.isEmpty()) {
            if (printOutput) {
                logger.lifecycle("No patches found")
            }
            return
        }

        // This prevents the `git am` command line from getting too big with too many patches
        // mostly an issue with Windows
        layout.cache.createDirectories()
        val tempDir = createTempDirectory(layout.cache, "paperweight")
        try {
            val mailDir = tempDir.resolve("new")
            mailDir.createDirectories()

            for (patch in patches) {
                patch.copyTo(mailDir.resolve(patch.fileName))
            }

            val gitOut = printOutput && verbose
            val result = git("am", "--3way", "--ignore-whitespace", tempDir.absolutePathString()).captureOut(gitOut)
            if (result.exit != 0) {
                statusFile.writeText("1")

                if (!gitOut) {
                    // Log the output anyway on failure
                    logger.lifecycle(result.out)
                }
                logger.error("***   Please review above details and finish the apply then")
                logger.error("***   save the changes with `./gradlew rebuildPatches`")

                throw PaperweightException("Failed to apply patches")
            } else {
                statusFile.deleteForcefully()
                if (printOutput) {
                    logger.lifecycle("${patches.size} patches applied cleanly to $target")
                }
            }
        } finally {
            tempDir.deleteRecursive()
        }
    }
}
