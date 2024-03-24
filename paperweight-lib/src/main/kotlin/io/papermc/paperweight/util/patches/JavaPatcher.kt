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

package io.papermc.paperweight.util.patches

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.Patch
import com.github.difflib.patch.PatchFailedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.streams.asStream

internal open class JavaPatcher : Patcher {

    override fun applyPatches(baseDir: Path, patchDir: Path, outputDir: Path, failedDir: Path): PatchResult {
        var result = baseDir.walk()
            .asStream()
            .map { original ->
                val relPath = original.relativeTo(baseDir)
                val patchPath = relPath.resolveSibling("${relPath.name}.patch").toString()
                val patch = patchDir.resolve(patchPath)
                val patched = outputDir.resolve(relPath.toString())
                PatchTask(patch, original, patched)
            }
            .filter { Files.isRegularFile(it.patch) }
            .parallel()
            .map { applyPatch(it) }
            .toList()
            .fold<_, PatchResult>(PatchSuccess) { acc, value ->
                acc.fold(value)
            }

        result = patchDir.walk()
            .filter { it.name.endsWith(".patch") }
            .filterNot { result.patches.contains(it) }
            .map { patch ->
                // all patches here did not have matching files
                // this results in a patch failure too
                val relPath = patch.relativeTo(patchDir)
                PatchFailure(patch, "No matching file found for patch: " + relPath.pathString)
            }
            .fold(result) { acc, value ->
                acc.fold(value)
            }

        return result
    }

    internal data class PatchTask(val patch: Path, val original: Path, val patched: Path)

    internal open fun applyPatch(task: PatchTask): PatchResult {
        val (patch, original, patched) = task

        patched.parent.createDirectories()
        if (patch.notExists()) {
            original.copyTo(patched)
            return PatchSuccess
        }

        val patchLines = patch.readLines(Charsets.UTF_8)
        val parsedPatch = UnifiedDiffUtils.parseUnifiedDiff(patchLines)
        val javaLines = original.readLines(Charsets.UTF_8)

        return try {
            val patchedLines = applyPatch(parsedPatch, javaLines)
            patched.writeText(
                patchedLines.joinToString("\n", postfix = "\n"),
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            PatchSuccess(patch)
        } catch (e: PatchFailedException) {
            // patch failed, so copy the file over without the patch applied
            original.copyTo(patched, overwrite = true)
            PatchFailure(patch, e.message ?: "unknown")
        }
    }

    @Throws(PatchFailedException::class)
    internal open fun applyPatch(patch: Patch<String>, lines: List<String>): List<String> {
        return DiffUtils.patch(lines, patch)
    }
}
