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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Always apply patches")
abstract class ApplyFeaturePatches : ControllableOutputTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val repo: DirectoryProperty

    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val patches: DirectoryProperty

    @get:Inject
    abstract val providers: ProviderFactory

    @get:Input
    abstract val verbose: Property<Boolean>

    override fun init() {
        printOutput.convention(false).finalizeValueOnRead()
        verbose.convention(false)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val repoPath = repo.convertToPath()

        val git = Git(repoPath)

        if (git("checkout", "main").runSilently(silenceErr = true) != 0) {
            git("checkout", "-b", "main").runSilently(silenceErr = true)
        }
        git("reset", "--hard", "file").executeSilently(silenceErr = true)
        git("gc").runSilently(silenceErr = true)

        applyGitPatches(git, "server repo", repoPath, patches.convertToPath(), printOutput.get(), verbose.get())
    }
}
