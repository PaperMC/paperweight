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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.commitAndTag
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.eclipse.jgit.api.Git
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class CopyAndTag : BaseTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:Input
    abstract val pathInInput: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val tag: Property<String>

    override fun init() {
        super.init()
        outputDir.set(layout.cache.resolve(paperTaskOutput()))
        tag.convention("base")
    }

    @TaskAction
    fun run() {
        outputDir.path.cleanDir()
        inputDir.path.resolve(pathInInput.get()).copyRecursivelyTo(outputDir.path)

        val git = Git.open(outputDir.path.toFile())
        commitAndTag(git, tag.get())
        git.close()
    }
}
