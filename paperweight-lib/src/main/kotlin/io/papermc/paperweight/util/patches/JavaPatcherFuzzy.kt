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

import com.github.difflib.patch.Patch

internal class JavaPatcherFuzzy(private val maxFuzz: Int) : JavaPatcher() {

    init {
        if (maxFuzz < 0) {
            throw IllegalArgumentException("max-fuzz argument must be a non-negative integer")
        }
    }

    override fun applyPatch(patch: Patch<String>, lines: List<String>): List<String> {
        return patch.applyFuzzy(lines, maxFuzz)
    }
}
