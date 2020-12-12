/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.PaperweightException
import java.io.File
import java.util.zip.ZipFile

object McDev {

    fun importMcDev(patches: Array<File>, decompJar: File, libraryImports: File, libraryDir: File, targetDir: File) {
        val importMcDev = readMcDevNames(patches).asSequence()
            .map { targetDir.resolve("net/minecraft/$it.java") }
            .filter { !it.exists() }
            .toSet()

        println("Importing ${importMcDev.size} classes from vanilla...")

        ZipFile(decompJar).use { zipFile ->
            for (file in importMcDev) {
                if (!file.parentFile.exists()) {
                    file.parentFile.mkdirs()
                }
                val zipEntry = zipFile.getEntry(file.relativeTo(targetDir).path) ?: continue
                zipFile.getInputStream(zipEntry).use { input ->
                    file.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        // Import library classes
        val libraryLines = libraryImports.readLines()
        if (libraryLines.isEmpty()) {
            return
        }

        val libFiles = (libraryDir.listFiles() ?: emptyArray()).filter { it.name.endsWith("-sources.jar" )}
        if (libFiles.isEmpty()) {
            throw PaperweightException("No library files found")
        }

        for (line in libraryLines) {
            val (libraryName, filePath) = line.split(" ")
            val libFile = libFiles.firstOrNull { it.name.startsWith(libraryName) }
                ?: throw PaperweightException("Failed to find library: $libraryName for class $filePath")

            val outputFile = targetDir.resolve(filePath)
            if (outputFile.exists()) {
                continue
            }
            outputFile.parentFile.mkdirs()
            ZipFile(libFile).use { zipFile ->
                val zipEntry = zipFile.getEntry(filePath)
                zipFile.getInputStream(zipEntry).use { input ->
                    outputFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun readMcDevNames(patches: Array<File>): Set<String> {
        val result = hashSetOf<String>()

        val prefix = "+++ b/src/main/java/net/minecraft/"
        val suffix = ".java"

        for (patch in patches) {
            patch.useLines { lines ->
                lines.filter { it.startsWith(prefix) }
                    .mapTo(result) { it.substring(prefix.length, it.length - suffix.length) }
            }
        }

        return result
    }
}
