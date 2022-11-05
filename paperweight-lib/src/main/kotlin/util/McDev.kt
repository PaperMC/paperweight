/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

object McDev {
    private val logger: Logger = Logging.getLogger(McDev::class.java)

    private val bannedClasses = setOf(
        "MCUtil",
        "ServerWorkerThread",
    )

    fun importMcDev(
        patches: Iterable<Path>,
        decompJar: Path,
        importsFile: Path?,
        librariesDirs: List<Path>?,
        targetDir: Path,
        printOutput: Boolean = true
    ) {
        val patchLines = readPatchLines(patches)

        decompJar.openZip().use { zipFile ->
            val decompFiles = mutableSetOf<String>()

            zipFile.walk().use { stream ->
                for (zipEntry in stream) {
                    // substring(1) trims the leading /
                    val path = zipEntry.invariantSeparatorsPathString.substring(1)

                    if (path.endsWith(".java")) {
                        decompFiles += path
                    }

                    // pull in all package-info classes
                    if (zipEntry.toString().endsWith("package-info.java")) {
                        val targetFile = targetDir.resolve(path)
                        if (targetFile.exists()) {
                            continue
                        }
                        if (!targetFile.parent.exists()) {
                            targetFile.parent.createDirectories()
                        }
                        zipEntry.copyTo(targetFile)
                    }
                }
            }

            val (importMcDev, banned) = readMcDevNames(patchLines, decompFiles, importsFile).asSequence()
                .map { targetDir.resolve("$it.java") }
                .filterNot { it.exists() }
                .distinct()
                .partition { file -> bannedClasses.none { file.toString().contains(it) } }

            logger.log(if (printOutput) LogLevel.LIFECYCLE else LogLevel.DEBUG, "Importing {} classes from vanilla...", importMcDev.size)

            banned.forEach {
                logger.log(if (printOutput) LogLevel.WARN else LogLevel.DEBUG, "Skipped importing '{}': File is banned", it.toString())
            }

            for (file in importMcDev) {
                if (!file.parent.exists()) {
                    file.parent.createDirectories()
                }
                val vanillaFile = file.relativeTo(targetDir).toString()

                val zipPath = zipFile.getPath(vanillaFile)
                if (zipPath.notExists()) {
                    logger.log(if (printOutput) LogLevel.WARN else LogLevel.DEBUG, "Skipped importing '{}': File not found", file.toString())
                    continue
                }
                zipPath.copyTo(file)
            }
        }

        val libFiles = librariesDirs?.flatMap { it.listDirectoryEntries("*-sources.jar") } ?: return
        if (libFiles.isEmpty()) {
            throw PaperweightException("No library files found")
        }

        // Import library classes
        val imports = findLibraries(importsFile, libFiles, patchLines)
        logger.log(if (printOutput) LogLevel.LIFECYCLE else LogLevel.DEBUG, "Importing {} classes from library sources...", imports.size)

        for ((libraryFileName, importFilePath) in imports) {
            val libFile = libFiles.firstOrNull { it.name == libraryFileName }
                ?: throw PaperweightException("Failed to find library: $libraryFileName for class $importFilePath")

            val outputFile = targetDir.resolve(importFilePath)
            if (outputFile.exists()) {
                continue
            }
            outputFile.parent.createDirectories()

            libFile.openZip().use { zipFile ->
                val libEntry = zipFile.getPath(importFilePath)
                libEntry.copyTo(outputFile)
            }
        }
    }

    private fun readPatchLines(patches: Iterable<Path>): Set<String> {
        val result = hashSetOf<String>()

        val prefix = "+++ b/src/main/java/"

        for (patch in patches) {
            patch.useLines { lines ->
                lines.filter { it.startsWith(prefix) }
                    .filterNot { file -> bannedClasses.any { file.contains(it) } }
                    .mapTo(result) { it.substring(prefix.length, it.length) }
            }
        }

        return result
    }

    private fun readMcDevNames(
        patchLines: Set<String>,
        decompFiles: Set<String>,
        additionalClasses: Path?
    ): Set<String> {
        val result = hashSetOf<String>()

        val suffix = ".java"

        patchLines.filter { decompFiles.contains(it) }
            .filterNot { file -> bannedClasses.any { file.contains(it) } }
            .mapTo(result) { it.removeSuffix(".java") }

        additionalClasses?.useLines { lines ->
            lines.filterNot { it.startsWith("#") }
                .mapNotNull {
                    val parts = it.split(" ")
                    if (parts[0] == "minecraft") {
                        parts[1]
                    } else {
                        null
                    }
                }
                .mapTo(result) { it.removeSuffix(suffix).replace('.', '/') }
        }

        return result
    }

    private fun findLibraries(libraryImports: Path?, libFiles: List<Path>, patchLines: Set<String>): Set<LibraryImport> {
        val result = hashSetOf<LibraryImport>()

        // Imports from library-imports.txt
        libraryImports?.useLines { lines ->
            lines.filterNot { it.startsWith('#') }
                .map { it.split(' ') }
                .filter { it.size == 2 }
                .filter { it[0] != "minecraft" }
                .mapTo(result) { parts ->
                    val libFileName = libFiles.firstOrNull { it.name.startsWith(parts[0]) }?.name
                        ?: throw PaperweightException("Failed to read library line '${parts[0]} ${parts[1]}', no library file was found.")
                    LibraryImport(libFileName, parts[1].removeSuffix(".java").replace('.', '/') + ".java")
                }
        }

        // Scan patches for necessary imports
        result += findNeededLibraryImports(patchLines, libFiles)

        return result
    }

    private fun findNeededLibraryImports(patchLines: Set<String>, libFiles: List<Path>): Set<LibraryImport> {
        val knownImportMap = findPossibleLibraryImports(libFiles)
            .associateBy { it.importFilePath }
        val prefix = "+++ b/src/main/java/"
        return patchLines.map { it.substringAfter(prefix) }
            .mapNotNull { knownImportMap[it] }
            .toSet()
    }

    private fun findPossibleLibraryImports(libFiles: List<Path>): Collection<LibraryImport> {
        val found = hashSetOf<LibraryImport>()
        val suffix = ".java"
        libFiles.map { libFile ->
            libFile.openZip().use { zipFile ->
                zipFile.walk()
                    .filter { it.isRegularFile() && it.name.endsWith(suffix) }
                    .map { sourceFile ->
                        LibraryImport(libFile.name, sourceFile.toString().substring(1))
                    }
                    .forEach(found::add)
            }
        }
        return found
    }

    private data class LibraryImport(val libraryFileName: String, val importFilePath: String)
}
