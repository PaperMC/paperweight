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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input

@CacheableTask
abstract class CloneRepo : ZippedTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val ref: Property<String>

    @get:Input
    abstract val shallowClone: Property<Boolean>

    override fun init() {
        super.init()
        shallowClone.convention(true)
    }

    override fun run(rootDir: Path) {
        Git.checkForGit()

        val urlText = url.get().trim()

        if (rootDir.resolve(".git").notExists()) {
            rootDir.deleteRecursive()
            rootDir.createDirectories()

            Git(rootDir)("init", "--quiet").executeSilently()
        }

        val git = Git(rootDir)
        git("remote", "add", "origin", urlText).executeSilently(silenceErr = true)
        git.fetch()

        git("checkout", "-f", "FETCH_HEAD").executeSilently(silenceErr = true)
    }

    private fun Git.fetch() {
        if (shallowClone.get()) {
            this("fetch", "--depth", "1", "origin", ref.get()).executeSilently(silenceErr = true)
        } else {
            this("fetch", "origin", ref.get()).executeSilently(silenceErr = true)
        }
    }
}
