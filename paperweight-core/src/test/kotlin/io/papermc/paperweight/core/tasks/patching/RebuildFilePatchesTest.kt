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

import io.papermc.paperweight.tasks.*
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gradle.kotlin.dsl.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

class RebuildFilePatchesTest : TaskTest() {
    private lateinit var task: RebuildFilePatches

    @BeforeTest
    fun setup() {
        val project = setupProject()
        project.plugins.apply("java")
        task = project.tasks.register("rebuildPatches", RebuildFilePatches::class).get()
    }

    // TODO
    @Disabled("worker api not available here")
    @Test
    fun `should rebuild patches`(@TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: Path) {
        println("running in $tempDir")
        val testResource = Path.of("src/test/resources/rebuild_patches")
        val testInput = testResource.resolve("input")

        val source = setupDir(tempDir, testInput, "source").toFile()
        setupGitRepo(source, "main", "file")
        setupGitRepo(tempDir.toFile(), "main", "dum")
        setupGitHook(source)
        val base = setupDir(tempDir, testInput, "base").toFile()
        val patches = tempDir.resolve("patches").toFile()
        val atFile = testInput.resolve("ats.at").toFile()
        val atFileOut = tempDir.resolve("ats.at").toFile()

        task.input.set(source)
        task.base.set(base)
        task.patches.set(patches)
        task.atFile.set(atFile)
        task.atFileOut.set(atFileOut)
        task.verbose.set(true)

        task.run()

        val testOutput = testResource.resolve("output")
        compareDir(tempDir, testOutput, "base")
        compareDir(tempDir, testOutput, "source")
        compareDir(tempDir, testOutput, "patches")
        compareFile(tempDir, testOutput, "ats.at")
    }
}
