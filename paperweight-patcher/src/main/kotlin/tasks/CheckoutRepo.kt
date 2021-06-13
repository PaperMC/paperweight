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

package io.papermc.paperweight.patcher.tasks

import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.path
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class CheckoutRepo : DefaultTask() {

    @get:Input
    abstract val repoName: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val ref: Property<String>

    @get:Input
    abstract val shallowClone: Property<Boolean>

    @get:Input
    abstract val initializeSubmodules: Property<Boolean>

    @get:Input
    abstract val initializeSubmodulesShallow: Property<Boolean>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }

        @Suppress("LeakingThis")
        run {
            repoName.finalizeValueOnRead()
            url.finalizeValueOnRead()
            ref.finalizeValueOnRead()
            shallowClone.convention(true).finalizeValueOnRead()
            initializeSubmodules.convention(false).finalizeValueOnRead()
            initializeSubmodulesShallow.convention(false).finalizeValueOnRead()

            outputDir.convention(workDir.dir(repoName)).finalizeValueOnRead()
        }
    }

    @TaskAction
    fun run() {
        val dir = outputDir.path
        val urlText = url.get().trim()

        if (dir.resolve(".git").notExists()) {
            dir.deleteRecursively()
            dir.createDirectories()

            Git(dir)("init", "--quiet").executeSilently()
        }

        val git = Git(dir)
        git("remote", "remove", "origin").runSilently(silenceErr = true) // can fail
        git("remote", "add", "origin", urlText).executeSilently(silenceErr = true)
        git.fetch()

        git("checkout", "-f", "FETCH_HEAD").executeSilently(silenceErr = true)
        git("clean", "-fqd").executeSilently(silenceErr = true)

        if (initializeSubmodules.get()) {
            git.updateSubmodules()
        }
    }

    private fun Git.fetch() {
        if (shallowClone.get()) {
            this("fetch", "--depth", "1", "origin", ref.get()).executeSilently(silenceErr = true)
        } else {
            this("fetch", "origin", ref.get()).executeSilently(silenceErr = true)
        }
    }

    private fun Git.updateSubmodules() {
        if (initializeSubmodulesShallow.get()) {
            this("submodule", "update", "--init", "--recursive", "--depth", "1").executeSilently(silenceErr = true)
        } else {
            this("submodule", "update", "--init", "--recursive").executeSilently(silenceErr = true)
        }
    }
}
