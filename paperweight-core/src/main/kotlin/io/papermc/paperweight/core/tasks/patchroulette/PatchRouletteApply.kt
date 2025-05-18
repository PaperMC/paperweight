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

/**
 * Patch roulette apply allows selection a set of patches from the remote patch roulette instance to work on.
 * To control the amount/strategy of selecting these patches, the `--select` option can be passed.
 * The following options are available:
 *   - `n`:  Any positive integer number.
 *           Paperweight will select *up to* `n` patches from the current package the user is working in.
 *           If the package offers 0 patches, a new package will be chosen.
 *           If the package offers `m` patches, and `m < n`, only `m` patches will be returned.
 *   - `n!`: Any positive integer number followed by a `!`.
 *           Paperweight will select `n` patches, prioritizing patches in the current package.
 *           The only time less than `n` patches will be selected is if the entire patch roulette
 *           instance has less than `n` patches available, in which case all of them will be selected.
 */
abstract class PatchRouletteApply : AbstractPatchRouletteTask() {

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

        // Nothing can be applied if the target repo is dirty.
        val git = Git(targetDir.path)
        val potentiallyDirtyTargetDir = git("status", "--porcelain").getText()
        if (potentiallyDirtyTargetDir.isNotEmpty()) {
            throw PaperweightException("Target directory is dirty, finish and rebuild previous patches first: [$potentiallyDirtyTargetDir]")
        }

        // Early opt out if someone is only attempting to re-apply
        if (this.reapplyPatches.getOrElse(false)) {
            logger.lifecycle("Reapplying ${config.currentPatches.size} currently selected patches")
            applyPatches(git, config.currentPatches)
            return
        }

        // Prevent acquiring new patches from roulette if current patches have not been marked as finished yet.
        if (config.currentPatches.isNotEmpty()) {
            throw PaperweightException("You already selected the patches [${config.currentPatches.joinToString(", ") { it.name }}]!")
        }

        var tries = 5
        var patches: List<Path>
        val patchSelectionStrategy = patchSelectionStrategy
            .map { PatchSelectionStrategy.parse(it) }
            .getOrElse(PatchSelectionStrategy.NumericInPackage(5))
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

            try {
                val startedPatches = startPatches(patches.map { it.invariantSeparatorsPathString })
                this.config.path.writeText(gson.toJson(config.copy(currentPatches = patches)))
                applyPatches(git, startedPatches.map { Path(it) })
                break
            } catch (e: PaperweightException) {
                logger.lifecycle("Patches could not be started: ${e.message}, retrying...")
                tries--
            }
        }
    }

    private fun applyPatches(git: Git, patches: List<Path>) {
        patches.forEach { patch ->
            val applyCommand = git(
                "-c",
                "rerere.enabled=false",
                "apply",
                "--3way",
                patchDir.path.resolve(patch).relativeTo(targetDir.path).invariantSeparatorsPathString
            )
            val errorTextBuffer = ByteArrayOutputStream()
            applyCommand.setup(System.out, errorTextBuffer)
            val applyCommandExitCode = applyCommand.run()
            val applyCommandErrorText = String(errorTextBuffer.toByteArray(), Charset.defaultCharset())
            val applyCommandErrorTextContainsUnexpected = applyCommandErrorText.lines()
                .filter { it.isNotBlank() }
                .any { !it.startsWith("Applied patch") && !it.startsWith("U ") } // Unexpected output, warn user to double check

            if (applyCommandExitCode > 1 || applyCommandErrorTextContainsUnexpected) {
                logger.error("===============================================")
                logger.error(applyCommandErrorText)
                logger.error("===============================================")
                logger.error("ERROR: Check above ^^^, Something might have gone wrong while applying!!")
                logger.error("ERROR: You might need to apply manually or check the target repo state.")
            }
        }
        logger.lifecycle("Finish the patch apply and rebuild, then run the finish task (or cancel with the cancel task)")
    }

    data class Config(val skip: List<Path>, val suggestedPackage: Path?, val currentPatches: List<Path>)

    sealed interface PatchSelectionStrategy {
        data class NumericInPackage(val count: Int, val enforceCount: Boolean = false) : PatchSelectionStrategy {
            override fun select(config: Config, available: List<Path>): Pair<Config, List<Path>> {
                return this.select(config, available, this.count)
            }

            fun select(config: Config, available: List<Path>, count: Int): Pair<Config, List<Path>> {
                if (config.suggestedPackage != null) {
                    val possiblePatches = available.filter { it.parent.equals(config.suggestedPackage) }.take(count)
                    if (possiblePatches.isNotEmpty()) {
                        if (!enforceCount) return config to possiblePatches
                        // The patches we found satisfy the count param or the entire available set simply does not offer enough patches.
                        if (possiblePatches.size >= count || possiblePatches.size == available.size) return config to possiblePatches

                        // The patches found in the package do not satisfy the requested count *and* the strategy was configured to enforce the
                        // count. Re-select from a new package and different patch set, add them to our already fetched patches and update the
                        // config, as the last suggested package is the one to suggest in potentially next runs.
                        val additionalPatches = select(
                            config.copy(suggestedPackage = null),
                            available.filter { !possiblePatches.contains(it) },
                            count - possiblePatches.size
                        )

                        return additionalPatches.first to possiblePatches + additionalPatches.second
                    }
                }

                return select(config.copy(suggestedPackage = available.first().parent), available, count)
            }
        }

        fun select(config: Config, available: List<Path>): Pair<Config, List<Path>>

        companion object {
            fun parse(input: String): PatchSelectionStrategy {
                try {
                    return when {
                        input.endsWith("!") -> NumericInPackage(input.substring(0, input.length - 1).toInt(), true)
                        else -> NumericInPackage(input.toInt())
                    }
                } catch (e: Exception) {
                    throw PaperweightException("Failed to parse patch selection strategy $input", e)
                }
            }
        }
    }
}
