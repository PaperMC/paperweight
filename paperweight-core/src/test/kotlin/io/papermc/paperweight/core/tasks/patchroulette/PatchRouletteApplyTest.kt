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

package io.papermc.paperweight.core.tasks.patchroulette

import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class PatchRouletteApplyTest {

    @Test
    fun `test patch strategy parsing non enforced`() {
        val strategy = PatchRouletteApply.PatchSelectionStrategy.parse("10")
        when (strategy) {
            is PatchRouletteApply.PatchSelectionStrategy.NumericInPackage -> {
                assertEquals(10, strategy.count)
                assertFalse(strategy.enforceCount)
            }
        }
    }

    @Test
    fun `test patch strategy parsing enforced`() {
        val strategy = PatchRouletteApply.PatchSelectionStrategy.parse("20!")
        when (strategy) {
            is PatchRouletteApply.PatchSelectionStrategy.NumericInPackage -> {
                assertEquals(20, strategy.count)
                assertTrue(strategy.enforceCount)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("testPatchSelectionSource")
    fun `test patch selection`(
        strategy: PatchRouletteApply.PatchSelectionStrategy,
        allPatches: List<String>,
        expectedPatchBatches: List<List<String>>
    ) {
        var config = PatchRouletteApply.Config(listOf(), null, listOf())
        var availablePatches = allPatches.map { Path(it) }
        for (batch in expectedPatchBatches) {
            val selectionResult = strategy.select(config, availablePatches)
            assertEquals(batch.map { Path(it) }, selectionResult.second)

            config = selectionResult.first
            availablePatches = availablePatches - selectionResult.second
        }

        assertTrue(availablePatches.isEmpty(), "Patches remained after exhausting expected batches")
    }

    companion object {
        @JvmStatic
        fun testPatchSelectionSource(): Collection<Arguments> = listOf(
            Arguments.of(
                PatchRouletteApply.PatchSelectionStrategy.NumericInPackage(2),
                mockAvailablePatches(),
                listOf(
                    listOf("io/papermc/paper/block/Block.java", "io/papermc/paper/block/BlockData.java"),
                    listOf("io/papermc/paper/block/BlockState.java"),
                    listOf("io/papermc/paper/entity/Entity.java")
                )
            ),
            Arguments.of(
                PatchRouletteApply.PatchSelectionStrategy.NumericInPackage(5),
                mockAvailablePatches(),
                listOf(
                    listOf("io/papermc/paper/block/Block.java", "io/papermc/paper/block/BlockData.java", "io/papermc/paper/block/BlockState.java"),
                    listOf("io/papermc/paper/entity/Entity.java")
                )
            ),
            Arguments.of(
                PatchRouletteApply.PatchSelectionStrategy.NumericInPackage(2, true),
                mockAvailablePatches(),
                listOf(
                    listOf("io/papermc/paper/block/Block.java", "io/papermc/paper/block/BlockData.java"),
                    listOf("io/papermc/paper/block/BlockState.java", "io/papermc/paper/entity/Entity.java"),
                )
            ),
            Arguments.of(
                PatchRouletteApply.PatchSelectionStrategy.NumericInPackage(5, true),
                mockAvailablePatches(),
                listOf(
                    listOf(
                        "io/papermc/paper/block/Block.java",
                        "io/papermc/paper/block/BlockData.java",
                        "io/papermc/paper/block/BlockState.java",
                        "io/papermc/paper/entity/Entity.java"
                    ),
                )
            ),
            Arguments.of(
                PatchRouletteApply.PatchSelectionStrategy.NumericInPackage(4, true),
                listOf(
                    "io/papermc/paper/block/Block.java",
                    "io/papermc/paper/block/BlockData.java",
                    "io/papermc/paper/block/BlockState.java",
                    "io/papermc/paper/entity/Entity.java",
                    "io/papermc/paper/entity/Entity2.java",
                    "io/papermc/paper/entity/Entity3.java"
                ),
                listOf(
                    listOf(
                        "io/papermc/paper/block/Block.java",
                        "io/papermc/paper/block/BlockData.java",
                        "io/papermc/paper/block/BlockState.java",
                        "io/papermc/paper/entity/Entity.java",
                    ),
                    listOf(
                        "io/papermc/paper/entity/Entity2.java",
                        "io/papermc/paper/entity/Entity3.java"
                    )
                )
            )
        )

        fun mockAvailablePatches() = listOf(
            "io/papermc/paper/block/Block.java",
            "io/papermc/paper/block/BlockData.java",
            "io/papermc/paper/block/BlockState.java",
            "io/papermc/paper/entity/Entity.java"
        )
    }
}
