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

package io.papermc.paperweight.checkstyle.tasks

import io.papermc.paperweight.tasks.TaskTest
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gradle.kotlin.dsl.register
import org.junit.jupiter.api.io.TempDir

class MergeCheckstyleConfigsTest : TaskTest() {
    private lateinit var task: MergeCheckstyleConfigs

    @BeforeTest
    fun setup() {
        val project = setupProject()
        task = project.tasks.register("mergeConfigs", MergeCheckstyleConfigs::class).get()
    }

    @Test
    fun basicMerge(@TempDir tempDir: Path) {
        val testResource = Path.of("src/test/resources/checkstyle/basicMerge")
        val testInput = testResource.resolve("input")

        val baseConfig = setupFile(tempDir, testInput, "base_checkstyle.xml")
        val overrideConfig = setupFile(tempDir, testInput, "project_checkstyle.xml")
        val output = tempDir.resolve("merged_checkstyle.xml")

        task.baseConfigFile.set(baseConfig.toFile())
        task.overrideConfigFile.set(overrideConfig.toFile())
        task.mergedConfigFile.set(output.toFile())

        task.run()

        val testOutput = testResource.resolve("output")
        compareFile(tempDir, testOutput, "merged_checkstyle.xml")
    }
}
