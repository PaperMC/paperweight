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

package io.papermc.paperweight.core.tasks.patchroulette

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile

abstract class PatchRouletteApply : AbstractPatchRouletteTask() {

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:OutputDirectory
    abstract val targetDir: DirectoryProperty

    @get:OutputFile
    abstract val config: RegularFileProperty

    override fun run() {
        config.path.createParentDirectories()
        var config = if (config.path.isRegularFile()) {
            gson.fromJson<Config>(config.path)
        } else {
            Config(listOf(), null)
        }

        if (config.currentPatch != null) {
            throw PaperweightException("You already have ${config.currentPatch} as current patch!")
        }

        var tries = 5
        var patch: String? = null
        while (tries > 0) {
            val available = getAvailablePatches().toMutableSet()

            if (available.isEmpty()) {
                throw PaperweightException("No patches available.")
            }

            val toRemove = config.skip.toMutableSet().also { it.retainAll(available) }
            available.removeAll(toRemove)
            if (available.isEmpty()) {
                logger.lifecycle("Only skipped patches remain!")
                available.addAll(toRemove)
            }

            patch = available.shuffled().first()

            logger.lifecycle("Picked patch $patch, ok? (y/n)")
            var response: String? = null
            while (response != "y" && response != "n") {
                response = System.`in`.bufferedReader().readLine()
            }
            if (response == "n") {
                config = config.copy(skip = config.skip + patch)
                this.config.path.writeText(gson.toJson(config))
                continue
            }

            try {
                startPatch(patch)
                this.config.path.writeText(gson.toJson(config.copy(currentPatch = patch)))
                break
            } catch (e: PaperweightException) {
                logger.lifecycle("Patch could not be started: ${e.message}, retrying...")
                tries--
            }
        }

        applyPatch(patch!!)
    }

    private fun applyPatch(patch: String) {
        val git = Git(targetDir.path)
        git(
            "-c",
            "rerere.enabled=false",
            "apply",
            "--3way",
            patchDir.path.resolve(patch).relativeTo(targetDir.path).invariantSeparatorsPathString
        ).runOut()
        logger.lifecycle("Finish the patch apply and rebuild, then run the finish task (or cancel with the cancel task)")
    }

    data class Config(val skip: List<String>, val currentPatch: String?)
}
