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
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.options.Option

abstract class PatchRouletteApply : AbstractPatchRouletteTask() {

    data class Config(val skip: List<Path>, val suggestedPackage: Path?, val currentPatches: List<Path>)

    sealed interface PatchSelectionStrategy {
        data class NumericInPackage(val count: Int): PatchSelectionStrategy {
            override fun select(config: Config, available: List<Path>): Pair<Config, List<Path>> {
                if (config.suggestedPackage != null) {
                    val possiblePatches = available.filter { it.parent.equals(config.suggestedPackage) }.take(count)
                    if (possiblePatches.isNotEmpty()) return config to possiblePatches
                }

                return select(config.copy(suggestedPackage = available.first().parent), available)
            }
        }

        fun select(config: Config, available: List<Path>): Pair<Config, List<Path>>
    }

    private fun parsePatchSelectionStrategy(input: String): PatchSelectionStrategy {
        try {
            return PatchSelectionStrategy.NumericInPackage(input.toInt())
        } catch (e: Exception) {
            throw PaperweightException("Failed to parse patch selection strategy $input", e)
        }
    }

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Input
    @get:Optional
    @get:Option(option = "select", description = "Selection strategy used for patches")
    abstract val patchSelectionStrategy: Property<String>

    @get:OutputDirectory
    abstract val targetDir: DirectoryProperty

    @get:OutputFile
    abstract val config: RegularFileProperty

    @get:Input
    @get:Optional
    @get:Option(option = "reapplyPatches", description = "Whether to reapply current selected patched")
    abstract val reapplyPatches: Property<Boolean>

    override fun run() {
        config.path.createParentDirectories()
        var config = if (config.path.isRegularFile()) {
            gson.fromJson<Config>(config.path)
        } else {
            Config(listOf(), null, listOf())
        }

        val git = Git(targetDir.path)
        if (this.reapplyPatches.getOrElse(false)) {
            applyPatches(git, config.currentPatches)
            return
        } else if (config.currentPatches.isNotEmpty()) {
            throw PaperweightException("You already selected the patches [${config.currentPatches.joinToString(", ") { it.name }}]!")
        }

        val potentiallyDirtyTargetDir = git("status", "--porcelain").getText()
        if (potentiallyDirtyTargetDir.isNotEmpty()) {
            throw PaperweightException("Target directory is dirty, finish and rebuild previous patches first: [$potentiallyDirtyTargetDir]")
        }

        var tries = 5
        var patches = listOf<Path>()
        val patchSelectionStrategy = patchSelectionStrategy.map { parsePatchSelectionStrategy(it) }.getOrElse(PatchSelectionStrategy.NumericInPackage(5))
        while (tries > 0) {
            val available = getAvailablePatches().map { Path(it) }.toMutableSet()

            if (available.isEmpty()) {
                throw PaperweightException("No patches available.")
            }

            val toRemove = config.skip.toMutableSet().also { it.retainAll(available) }
            available.removeAll(toRemove)
            if (available.isEmpty()) {
                logger.lifecycle("Only skipped patches remain!")
                available.addAll(toRemove)
            }

            val selectionResult = patchSelectionStrategy.select(config, available.shuffled())
            config = selectionResult.first
            patches = selectionResult.second

            logger.lifecycle("Picked the following ${patches.size}, ok? (y/n)")
            logger.lifecycle("===============================================")
            patches.forEach { logger.lifecycle(it.pathString) }

            var response: String? = null
            while (response != "y" && response != "n") {
                response = System.`in`.bufferedReader().readLine()
            }
            if (response == "n") {
                config = config.copy(skip = config.skip + patches)
                this.config.path.writeText(gson.toJson(config))
                continue
            }

            val startedPatches = mutableListOf<Path>()
            try {
                patches.forEach {
                    startPatch(it.pathString)
                    startedPatches.add(it)
                }
                this.config.path.writeText(gson.toJson(config.copy(currentPatches = patches)))
                break
            } catch (e: PaperweightException) {
                logger.lifecycle("Patch could not be started: ${e.message}, retrying...")
                startedPatches.forEach { cancelPatch(it.pathString) }
                tries--
            }
        }

        applyPatches(git, patches)
    }

    private fun applyPatches(git: Git, patches: List<Path>) {
        val applyCommand = git(
            "-c",
            "rerere.enabled=false",
            "apply",
            "--3way",
            *patches.map { patchDir.path.resolve(it).relativeTo(targetDir.path).invariantSeparatorsPathString }.toTypedArray()
        )
        val errorTextBuffer = ByteArrayOutputStream()
        applyCommand.setup(System.out, errorTextBuffer)
        val applyCommandExitCode = applyCommand.run()
        val applyCommandErrorText = String(errorTextBuffer.toByteArray(), Charset.defaultCharset())

        if (applyCommandExitCode > 1 || applyCommandErrorText.lines().filter { it.isNotBlank() }.any {
            !it.startsWith("Applied patch") && !it.startsWith("U ") // Unexpected output, warn user to double check
        }) {
            logger.error("===============================================")
            logger.error(applyCommandErrorText)
            logger.error("===============================================")
            logger.error("ERROR: Check above ^^^, Something might have gone wrong while applying!!")
            logger.error("ERROR: You might need to apply manually or check the target repo state.")
        }
        logger.lifecycle("Finish the patch apply and rebuild, then run the finish task (or cancel with the cancel task)")
    }
}
