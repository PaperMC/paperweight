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

package io.papermc.paperweight.tasks

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.junit.jupiter.api.io.TempDir

class ApplyAccessTransformTest : TaskTest() {
    private lateinit var task: ApplyAccessTransform

    private val workerExecutor: WorkerExecutor = mockk()
    private val workQueue: WorkQueue = mockk()

    @BeforeTest
    fun setup() {
        val project = setupProject()
        project.apply(plugin = "java")
        task = project.tasks.register("applyAccessTransform", ApplyAccessTransform::class).get()
        mockkObject(task)

        every { task.workerExecutor } returns workerExecutor
        every { workerExecutor.processIsolation(any()) } returns workQueue
        every { workQueue.submit(ApplyAccessTransform.AtlasAction::class, any()) } answers {
            val action = object : ApplyAccessTransform.AtlasAction() {
                override fun getParameters(): ApplyAccessTransform.AtlasParameters {
                    return mockk<ApplyAccessTransform.AtlasParameters>().also {
                        every { it.inputJar.get() } returns task.inputJar.get()
                        every { it.atFile.get() } returns task.atFile.get()
                        every { it.outputJar.get() } returns task.outputJar.get()
                    }
                }
            }
            action.execute()
        }
    }

    @Test
    fun `should apply access transform`(@TempDir tempDir: Path) {
        val testResource = Path.of("src/test/resources/apply_access_transform")
        val testInput = testResource.resolve("input")

        val input = createJar(tempDir, testInput, "Test").toFile()
        val output = tempDir.resolve("output.jar").toFile()
        val atFile = testInput.resolve("ats.at").toFile()

        task.inputJar.set(input)
        task.outputJar.set(output)
        task.atFile.set(atFile)

        task.run()

        val testOutput = testResource.resolve("output")
        compareJar(tempDir, testOutput, "output", "Test")
    }
}
