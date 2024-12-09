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

package io.papermc.paperweight.util

import io.papermc.paperweight.restamp.atFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.ModifierChange

class ATTest {

    @Test
    fun testATFromString() {
        assertEquals(AccessTransform.of(AccessChange.PUBLIC, ModifierChange.REMOVE), atFromString("public-f"))
        assertEquals(AccessTransform.of(AccessChange.PUBLIC, ModifierChange.NONE), atFromString("public"))
        assertEquals(AccessTransform.of(AccessChange.PUBLIC, ModifierChange.ADD), atFromString("public+f"))

        assertEquals(AccessTransform.of(AccessChange.PRIVATE, ModifierChange.REMOVE), atFromString("private-f"))
        assertEquals(AccessTransform.of(AccessChange.PRIVATE, ModifierChange.NONE), atFromString("private"))
        assertEquals(AccessTransform.of(AccessChange.PRIVATE, ModifierChange.ADD), atFromString("private+f"))

        assertEquals(AccessTransform.of(AccessChange.NONE, ModifierChange.REMOVE), atFromString("-f"))
        assertEquals(AccessTransform.of(AccessChange.NONE, ModifierChange.ADD), atFromString("+f"))
    }
}
