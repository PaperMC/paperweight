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

package io.papermc.paperweight.userdev.internal.action

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class WorkDispatcherTest {
    @Test
    fun testExecution(@TempDir work: Path) {
        val dispatcher = WorkDispatcher.create(work)

        val executed = mutableListOf<String>()

        val writeText = dispatcher.register(
            "writeText",
            WriteText(
                StringValue("hello, world"),
                dispatcher.outputFile("output.txt"),
            ) { executed.add("writeText") }
        )
        dispatcher.provided(writeText.initialData)
        val doubleText = dispatcher.register(
            "doubleText",
            DoubleText(
                writeText.outputFile,
                dispatcher.outputFile("output.txt"),
            ) { executed.add("doubleText") }
        )
        val doubleText2 = dispatcher.register(
            "doubleText2",
            DoubleText(
                writeText.outputFile,
                dispatcher.outputFile("output.txt"),
            ) { executed.add("doubleText2") }
        )
        val combine = dispatcher.register(
            "combine",
            CombineText(
                writeText.outputFile,
                doubleText.outputFile,
                dispatcher.outputFile("output.txt"),
            ) { executed.add("combine") }
        )

        dispatcher.dispatch(
            doubleText.outputFile,
            doubleText2.outputFile,
            combine.outputFile,
        )

        // Assert execution happened in the correct order
        assertEquals(listOf("writeText", "doubleText", "doubleText2", "combine"), executed)

        executed.clear()

        dispatcher.dispatch(
            doubleText.outputFile,
            doubleText2.outputFile,
            combine.outputFile,
        )

        // Assert no execution happened when up-to-date
        assertEquals(listOf(), executed)
    }

    class WriteText(
        @Input
        val initialData: StringValue,
        @Output
        val outputFile: FileValue,
        private val extraAction: Runnable,
    ) : WorkDispatcher.Action {
        override fun execute() {
            val initial = initialData.get()
            outputFile.get().cleanFile().writeText(initial)
            extraAction.run()
        }
    }

    class DoubleText(
        @Input
        val inputFile: FileValue,
        @Output
        val outputFile: FileValue,
        private val extraAction: Runnable,
    ) : WorkDispatcher.Action {
        override fun execute() {
            val input = inputFile.get().readText()
            val output = "$input\n$input"
            outputFile.get().cleanFile().writeText(output)
            extraAction.run()
        }
    }

    class CombineText(
        @Input
        val inputFile: FileValue,
        @Input
        val inputFile2: FileValue,
        @Output
        val outputFile: FileValue,
        private val extraAction: Runnable,
    ) : WorkDispatcher.Action {
        override fun execute() {
            val input = inputFile.get().readText()
            val input2 = inputFile2.get().readText()
            val output = "$input\n$input2"
            outputFile.get().cleanFile().writeText(output)
            extraAction.run()
        }
    }
}
