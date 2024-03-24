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

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import org.gradle.process.ExecOperations

internal open class NativePatcher(private val exec: ExecOperations, protected val patchExecutable: String) : Patcher {

    override fun applyPatches(baseDir: Path, patchDir: Path, outputDir: Path, failedDir: Path): PatchResult {
        baseDir.walk()
            .forEach { original ->
                val relPath = original.relativeTo(baseDir)
                val patched = outputDir.resolve(relPath.toString())
                patched.parent.createDirectories()
                original.copyTo(patched)
            }

        val result = patchDir.walk()
            .fold<_, PatchResult>(PatchSuccess) { acc, value ->
                acc.fold(patch(outputDir, failedDir, patchDir, value))
            }

        return result
    }

    internal open fun commandLineArgs(patch: Path): List<String> {
        return listOf(patchExecutable, "-u", "-p1", "--merge=diff3", "-i", patch.absolutePathString())
    }

    private fun patch(out: Path, failed: Path, patchDir: Path, patch: Path): PatchResult {
        val baos = ByteArrayOutputStream()
        val res = exec.exec {
            commandLine(commandLineArgs(patch))
            workingDir(out)

            standardOutput = baos
            errorOutput = baos

            isIgnoreExitValue = true
        }

        if (res.exitValue == 0) {
            return PatchSuccess
        } else {
            val inputFile = patch.resolveSibling(patch.nameWithoutExtension)
            val inputFilePath = inputFile.relativeTo(patchDir).toString()
            val input = out.resolve(inputFilePath)

            if (input.exists()) {
                val failedOutput = failed.resolve(inputFilePath)
                failedOutput.parent.createDirectories()
                input.moveTo(failedOutput)
            }
        }

        return PatchFailure(patch, baos.toString(Charset.defaultCharset()))
    }
}
