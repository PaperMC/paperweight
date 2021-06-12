/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.PaperweightException
import java.nio.file.Path
import kotlin.io.path.*

object McDev {

    private val bannedClasses = setOf(
        "KeyedObject",
        "MCUtil"
    )

    fun importMcDev(patches: Iterable<Path>, decompJar: Path, libraryImports: Path?, libraryDir: Path?, additionalClasses: Path?, targetDir: Path) {
        val importMcDev = readMcDevNames(patches, additionalClasses).asSequence()
            .map { targetDir.resolve("net/minecraft/$it.java") }
            .filter { !it.exists() }
            .filterNot { file -> bannedClasses.any { file.toString().contains(it) } }
            .toSet()

        println("Importing ${importMcDev.size} classes from vanilla...")

        decompJar.openZip().use { zipFile ->
            for (file in importMcDev) {
                if (!file.parent.exists()) {
                    file.parent.createDirectories()
                }
                val vanillaFile = file.relativeTo(targetDir).toString()

                val zipPath = zipFile.getPath(vanillaFile)
                if (zipPath.notExists()) {
                    throw PaperweightException("Vanilla class not found: $vanillaFile")
                }
                zipPath.copyTo(file)
            }
        }

        // Import library classes
        val libraryLines = libraryImports?.readLines() ?: emptyList()
        if (libraryLines.isEmpty()) {
            return
        }

        val libFiles = libraryDir?.listDirectoryEntries("*-sources.jar") ?: return
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
            outputFile.parent.createDirectories()

            libFile.openZip().use { zipFile ->
                val libEntry = zipFile.getPath(filePath)
                libEntry.copyTo(outputFile)
            }
        }
    }

    private fun readMcDevNames(patches: Iterable<Path>, additionalClasses: Path?): Set<String> {
        val result = hashSetOf<String>()

        val prefix = "+++ b/src/main/java/net/minecraft/"
        val suffix = ".java"

        for (patch in patches) {
            patch.useLines { lines ->
                lines.filter { it.startsWith(prefix) }
                    .filterNot { file -> bannedClasses.any { file.contains(it) } }
                    .mapTo(result) { it.substring(prefix.length, it.length - suffix.length) }
            }
        }

        additionalClasses?.useLines { lines ->
            lines.filterNot { it.startsWith("#") }
                .forEach { line ->
                    result += line.removeSuffix(".java").replace('.', '/').removePrefix("net/minecraft/")
                }
        }

        return result
    }
}
