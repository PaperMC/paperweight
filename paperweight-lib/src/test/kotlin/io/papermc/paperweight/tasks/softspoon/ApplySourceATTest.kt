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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gradle.kotlin.dsl.*
import org.junit.jupiter.api.io.TempDir

class ApplySourceATTest : TaskTest() {
    private lateinit var task: ApplySourceAT

    @BeforeTest
    fun setup() {
        val project = setupProject()
        task = project.tasks.register("applySourceAT", ApplySourceAT::class).get()
    }

    @Test
    fun `should apply source access transformers`(@TempDir tempDir: Path) {
        val testResource = Path.of("src/test/resources/apply_source_at")
        val testInput = testResource.resolve("input")

        val inputJar = createZip(tempDir, testInput, "Test.jar", "Test.java", "Unrelated.java")
        val atFile = testInput.resolve("ats.at").toFile()
        val outputJar = tempDir.resolve("output.jar")

        task.inputJar.set(inputJar.toFile())
        task.atFile.set(atFile)
        task.outputJar.set(outputJar.toFile())

        task.run()

        val testOutput = testResource.resolve("output")
        val expectedJar = createZip(tempDir, testOutput, "expected.jar", "Test.java", "Unrelated.java")
        compareZip(outputJar, expectedJar)
    }
}
