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

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FileUtilsTest {
    @Test
    fun testContentEquals(@TempDir tempDir: Path) {
        val someBytes = classBytes<FileUtilsTest>()

        // write same data to two files
        val one = tempDir.resolve("${FileUtilsTest::class.simpleName}.class")
        one.writeBytes(someBytes)
        val two = tempDir.resolve("${FileUtilsTest::class.simpleName}.class_1")
        two.writeBytes(someBytes)

        // assert the content equals what we just wrote
        assertTrue(one.contentEquals(someBytes.inputStream()), "File content doesn't equal what was written to the file")

        // assert the files have matching content
        assertTrue(one.contentEquals(two), "These files have the same content")
        assertTrue(two.contentEquals(one), "These files have the same content")

        val someDifferentBytes = classBytes<Map<*, *>>()

        // write some different data to a third file
        val three = tempDir.resolve("Map.class")
        three.writeBytes(someDifferentBytes)

        // assert it's content is different from the previously written files
        assertFalse(one.contentEquals(three), "These files are different")
    }

    private inline fun <reified C> classBytes(): ByteArray {
        val resourceName = C::class.java.name.replace(".", "/") + ".class"
        return this::class.java.classLoader.getResource(resourceName)
            ?.openStream()?.readAllBytes() ?: error("Couldn't get resource $resourceName")
    }
}
