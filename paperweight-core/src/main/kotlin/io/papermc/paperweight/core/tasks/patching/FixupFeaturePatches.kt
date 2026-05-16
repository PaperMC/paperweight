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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.listDirectoryEntries
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

@UntrackedTask(because = "Always fixup when requested")
abstract class FixupFeaturePatches : BaseTask() {

    @get:InputDirectory
    abstract val repo: DirectoryProperty

    @get:Input
    abstract val upstream: Property<String>

    @get:InputDirectory
    abstract val patches: DirectoryProperty

    @get:Input
    @get:Optional
    @get:Option(option = "patch-number", description = "Select the patch to modify")
    abstract val patchNumber: Property<Int>

    @TaskAction
    fun run() {
        val git = Git(repo)
        var index = -1
        if (patchNumber.isPresent) {
            index = patchNumber.get() - 1 // -1 as the commits index starts from 0 whereas patches start from 1
        } else {
            logger.lifecycle("===============================================")
            logger.lifecycle("Please enter the patch number into which the current changes should be merged")
            logger.lifecycle("===============================================")
            val patches = patches.get().path.listDirectoryEntries("*.patch").toList().sortedBy { it.fileName.toString().substringBefore("-").toInt() }
            logger.lifecycle("Possible patches:")
            for (patch in patches) {
                logger.lifecycle(patch.fileName.toString())
            }
            logger.lifecycle("===============================================")
            while (index == -1) {
                index = System.`in`.bufferedReader().readLine().toInt() - 1
            }
        }
        val commits = git("rev-list", "file..HEAD").getText().trim().lines().filter { it.isNotBlank() }.reversed()
        if (index < 0 || index >= commits.size) {
            error("Patch index out of range: $index (size=${commits.size})")
        }
        val selectedCommit = commits[index]
        git("add", ".").executeOut()
        git("commit", "--fixup", selectedCommit).executeOut()
        git("-c", "sequence.editor=:", "rebase", "-i", "--autosquash", upstream.get()).executeOut()
    }
}
