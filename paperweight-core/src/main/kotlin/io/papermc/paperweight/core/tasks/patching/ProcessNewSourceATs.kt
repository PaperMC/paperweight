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
import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.*

@UntrackedTask(because = "Always process when requested")
abstract class ProcessNewSourceATs : JavaLauncherTask() {

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:InputDirectory
    abstract val base: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val atFile: RegularFileProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @TaskAction
    fun run() {
        val inputDir = input.convertToPath()
        val baseDir = base.convertToPath()

        // find ATs, cleanup comment, apply to base
        val newATs = handleAts(baseDir, inputDir, atFile)

        // save work and jump to AT commit
        val git = Git(inputDir)
        git("stash", "push").executeSilently(silenceErr = true)
        git("checkout", MACHE_TAG_ATS).executeSilently(silenceErr = true)

        // apply new ATs to source
        newATs.forEach { (path, ats) ->
            val source = inputDir.resolve(path)
            applyNewATs(source, ats)
        }

        // commit new ATs
        git("add", ".").executeSilently(silenceErr = true)
        git("commit", "--amend", "--no-edit").executeSilently(silenceErr = true)

        // clean up tree: rebasing drops the old AT commit and replaces it with the new one
        git("switch", "-").executeSilently(silenceErr = true)
        git("rebase", MACHE_TAG_ATS).executeSilently(silenceErr = true)

        git("stash", "pop").executeSilently(silenceErr = true)
    }

    private fun handleAts(
        baseDir: Path,
        inputDir: Path,
        atFile: RegularFileProperty
    ): MutableList<Pair<String, AccessTransformSet>> {
        val oldAts = AccessTransformFormats.FML.read(atFile.path)
        val newATs = mutableListOf<Pair<String, AccessTransformSet>>()

        baseDir.walk()
            .map { it.relativeTo(baseDir).invariantSeparatorsPathString }
            .filter { it.endsWith(".java") }
            .forEach {
                val ats = AccessTransformSet.create()
                val source = inputDir.resolve(it)
                val decomp = baseDir.resolve(it)
                val className = it.replace(".java", "")
                if (findATInSource(source, ats, className)) {
                    applyNewATs(decomp, ats)
                    newATs.add(it to ats)
                }
                oldAts.merge(ats)
            }

        AccessTransformFormats.FML.writeLF(
            atFile.path,
            oldAts,
            "# This file is auto generated, any changes may be overridden!\n# See CONTRIBUTING.md on how to add access transformers.\n"
        )

        return newATs
    }

    private fun applyNewATs(decomp: Path, newAts: AccessTransformSet) {
        if (newAts.classes.isEmpty()) {
            return
        }

        val at = temporaryDir.toPath().resolve("ats.cfg").createParentDirectories()
        AccessTransformFormats.FML.writeLF(at, newAts)
        ats.run(
            launcher.get(),
            decomp,
            decomp,
            at,
            temporaryDir.toPath().resolve("jst_work").cleanDir(),
            singleFile = true,
        )
    }

    private fun findATInSource(source: Path, newAts: AccessTransformSet, className: String): Boolean {
        if (!source.exists()) {
            // source was added via lib imports
            return false
        }

        val sourceLines = source.readLines()
        var foundNew = false
        val fixedLines = ArrayList<String>(sourceLines.size)
        sourceLines.forEach { line ->
            if (!line.contains("// Paper-AT: ")) {
                fixedLines.add(line)
                return@forEach
            }

            foundNew = true

            val split = line.split("// Paper-AT: ")
            val at = split[1]
            try {
                val atClass = newAts.getOrCreateClass(className)
                val parts = at.split(" ")
                val accessTransform = atFromString(parts[0])
                val name = parts[1]
                val index = name.indexOf('(')
                if (index == -1) {
                    atClass.mergeField(name, accessTransform)
                } else {
                    atClass.mergeMethod(MethodSignature.of(name.substring(0, index), name.substring(index)), accessTransform)
                }
                logger.lifecycle("Found new AT in $className: $at -> $accessTransform")
            } catch (ex: Exception) {
                throw PaperweightException("Found invalid AT '$at' in class $className")
            }

            fixedLines.add(split[0].trimEnd())
        }

        if (foundNew) {
            source.writeText(fixedLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }

        return foundNew
    }
}
