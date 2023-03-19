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

import io.papermc.paperweight.PaperweightException
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

object McDev {
    private val logger: Logger = Logging.getLogger(McDev::class.java)

    fun importMcDev(
        patches: Iterable<Path>,
        decompJar: Path,
        importsFile: Path?,
        targetDir: Path,
        dataTargetDir: Path? = null,
        librariesDirs: List<Path> = listOf(),
        printOutput: Boolean = true
    ) {
        val (javaPatchLines, dataPatchLines) = readPatchLines(patches)

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

            val exactJavaImports = javaPatchLines.filter { decompFiles.contains(it) }
                .map { targetDir.resolve(it) }
            val exactDataImports = if (dataTargetDir != null) dataPatchLines.map { dataTargetDir.resolve("data/minecraft/$it") } else listOf()

            val (additionalSrcImports, additionalDataImports) = readAdditionalImports(importsFile)

            val srcMatcherImports = additionalSrcImports.distinct()
                .map { zipFile.getPathMatcher("glob:/$it.java") }
            val dataMatcherImports = additionalDataImports.distinct()
                .map { zipFile.getPathMatcher("glob:/data/minecraft/$it") }

            val (srcImportMcDev, dataImportMcDev) = zipFile.walk().use { stream ->
                val src = hashSetOf<Path>()
                val data = hashSetOf<Path>()
                stream.forEach { file ->
                    if (srcMatcherImports.any { it.matches(file) }) {
                        src.add(targetDir.resolve(file.invariantSeparatorsPathString.substring(1)))
                    } else if (dataTargetDir != null && dataMatcherImports.any { it.matches(file) }) {
                        data.add(dataTargetDir.resolve(file.invariantSeparatorsPathString.substring(1)))
                    }
                }
                Pair((src + exactJavaImports).filterNot { it.exists() }, (data + exactDataImports).filterNot { it.exists() })
            }

            logger.log(if (printOutput) LogLevel.LIFECYCLE else LogLevel.DEBUG, "Importing {} classes from vanilla...", srcImportMcDev.size)

            importFiles(srcImportMcDev, targetDir, zipFile, printOutput)

            if (dataTargetDir != null) {
                logger.log(if (printOutput) LogLevel.LIFECYCLE else LogLevel.DEBUG, "Importing {} data files from vanilla data...", dataImportMcDev.size)

                importFiles(dataImportMcDev, dataTargetDir, zipFile, printOutput, true)
            }
        }

        if (librariesDirs.isEmpty()) {
            return
        }
        val libFiles = librariesDirs.flatMap { it.listDirectoryEntries("*-sources.jar") }
        if (libFiles.isEmpty()) {
            throw PaperweightException("No library files found")
        }

        // Import library classes
        val imports = findLibraries(importsFile, libFiles, javaPatchLines)
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

    private fun importFiles(files: List<Path>, targetDir: Path, zipFile: FileSystem, printOutput: Boolean, checkFinalNewline: Boolean = false) {
        for (file in files) {
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
            if (checkFinalNewline) {
                var content = file.readText(Charsets.UTF_8)
                if (!content.endsWith("\n")) {
                    content += "\n"
                    file.writeText(content, Charsets.UTF_8)
                }
            }
        }
    }

    private fun readPatchLines(patches: Iterable<Path>): Pair<Set<String>, Set<String>> {
        val srcResult = hashSetOf<String>()
        val dataResult = hashSetOf<String>()

        val javaPrefix = "+++ b/src/main/java/"
        val dataPrefix = "+++ b/src/main/resources/data/minecraft/"

        for (patch in patches) {
            patch.useLines { lines ->
                val matches = lines.partition {
                    it.startsWith(javaPrefix)
                }
                matches.first
                    .mapTo(srcResult) { it.substring(javaPrefix.length, it.length) }
                matches.second
                    .filter { it.startsWith(dataPrefix) }
                    .mapTo(dataResult) { it.substring(dataPrefix.length, it.length) }
            }
        }

        return Pair(srcResult, dataResult)
    }

    private fun readAdditionalImports(
        additionalClasses: Path?
    ): Pair<Set<String>, Set<String>> {
        val srcResult = hashSetOf<String>()
        val dataResult = hashSetOf<String>()

        val suffix = ".java"

        additionalClasses?.useLines { lines ->
            lines.filterNot { it.startsWith("#") }
                .forEach {
                    val parts = it.split(" ")
                    if (parts[0] == "minecraft") {
                        srcResult += parts[1].removeSuffix(suffix).replace('.', '/')
                    } else if (parts[0] == "mc_data") {
                        dataResult += parts[1]
                    }
                }
        }

        return Pair(srcResult, dataResult)
    }

    private fun findLibraries(libraryImports: Path?, libFiles: List<Path>, patchLines: Set<String>): Set<LibraryImport> {
        val result = hashSetOf<LibraryImport>()

        // Imports from library-imports.txt
        libraryImports?.useLines { lines ->
            lines.filterNot { it.startsWith('#') }
                .map { it.split(' ') }
                .filter { it.size == 2 }
                .filter { it[0] != "minecraft" && it[0] != "mc_data" }
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
