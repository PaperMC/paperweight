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

import java.nio.file.Path

internal interface Patcher {

    fun applyPatches(baseDir: Path, patchDir: Path, outputDir: Path, failedDir: Path): PatchResult
}

internal sealed interface PatchResult {
    val patches: List<Path>
    val failures: List<PatchFailureDetails>
        get() = emptyList()

    fun fold(next: PatchResult): PatchResult {
        return when {
            this is PatchSuccess && next is PatchSuccess -> PatchSuccess(this.patches + next.patches)
            else -> PatchFailure(this.patches + next.patches, this.failures + next.failures)
        }
    }
}

internal sealed interface PatchSuccess : PatchResult {
    companion object : PatchSuccess {

        operator fun invoke(patches: List<Path> = emptyList()): PatchSuccess = PatchSuccessFile(patches)
        operator fun invoke(patch: Path): PatchSuccess = PatchSuccessFile(listOf(patch))

        override val patches: List<Path>
            get() = emptyList()
    }
}

private data class PatchSuccessFile(override val patches: List<Path>) : PatchSuccess

internal data class PatchFailure(override val patches: List<Path>, override val failures: List<PatchFailureDetails>) : PatchResult {
    constructor(patch: Path, details: String) : this(listOf(patch), listOf(PatchFailureDetails(patch, details)))
}

internal data class PatchFailureDetails(val patch: Path, val details: String)
