/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.copyRecursively
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional

abstract class ApplyRawDiffPatches : ZippedTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    override fun init() {
        outputZip.convention(defaultOutput("zip"))
    }

    override fun run(rootDir: Path) {
        val input = inputDir.path
        input.copyRecursively(rootDir)

        val patches = patchDir.pathOrNull ?: return
        val patchSet = patches.useDirectoryEntries("*.patch") { it.toMutableList() }

        patchSet.sort()

        val git = Git(rootDir)

        for (patch in patchSet) {
            git("apply", patch.absolutePathString()).executeOut()
        }
    }
}
