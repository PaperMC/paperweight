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
import kotlin.io.path.absolutePathString
import org.gradle.process.ExecOperations

internal class NativePatcherFuzzy(exec: ExecOperations, ex: String, private val maxFuzz: Int) : NativePatcher(exec, ex) {

    init {
        if (maxFuzz < 0) {
            throw IllegalArgumentException("max-fuzz argument must be a non-negative integer")
        }
    }

    override fun commandLineArgs(patch: Path): List<String> {
        return listOf(patchExecutable, "-u", "-p1", "--fuzz=$maxFuzz", "--merge=diff3", "-i", patch.absolutePathString())
    }
}
