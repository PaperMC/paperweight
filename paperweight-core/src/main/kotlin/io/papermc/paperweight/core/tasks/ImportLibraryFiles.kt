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

package io.papermc.paperweight.core.tasks

import com.github.salomonbrys.kotson.typeToken
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

private data class LibraryImport(val libraryFileName: String, val importFilePath: String)

abstract class IndexLibraryFiles : BaseTask() {

    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        super.init()
        outputFile.set(layout.cache.resolve(paperTaskOutput("json.gz")))
    }

    @TaskAction
    fun run() {
        val possible = ioDispatcher("IndexLibraryFiles").use { dispatcher ->
            findPossibleLibraryImports(libraries.sourcesJars(), dispatcher)
                .groupBy { it.libraryFileName }
                .mapValues {
                    it.value.map { v -> v.importFilePath }
                }
        }

        outputFile.path.cleanFile().outputStream().gzip().bufferedWriter().use { writer ->
            gson.toJson(possible, writer)
        }
    }

    private fun findPossibleLibraryImports(libFiles: List<Path>, dispatcher: CoroutineDispatcher): Collection<LibraryImport> = runBlocking {
        val found = ConcurrentHashMap.newKeySet<LibraryImport>()
        val suffix = ".java"
        libFiles.forEach { libFile ->
            launch(dispatcher) {
                libFile.openZipSafe().use { zipFile ->
                    zipFile.walkSequence()
                        .filter { it.isRegularFile() && it.name.endsWith(suffix) }
                        .map { sourceFile ->
                            LibraryImport(libFile.name, sourceFile.toString().substring(1))
                        }
                        .forEach(found::add)
                }
            }
        }
        return@runBlocking found
    }
}

abstract class ImportLibraryFiles : BaseTask() {

    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:InputFile
    abstract val libraryFileIndex: RegularFileProperty

    @get:Optional
    @get:InputFiles
    abstract val patches: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        super.init()
        outputDir.set(layout.cache.resolve(paperTaskOutput()))
    }

    @TaskAction
    fun run() {
        outputDir.path.deleteRecursive()
        outputDir.path.createDirectories()
        if (!libraries.isEmpty && !patches.isEmpty) {
            val index = libraryFileIndex.path.inputStream().gzip().bufferedReader().use { reader ->
                gson.fromJson<Map<String, List<String>>>(reader, typeToken<Map<String, List<String>>>())
            }.flatMap { entry -> entry.value.map { LibraryImport(entry.key, it) } }.toSet()
            val patchFiles = patches.files.flatMap { it.toPath().filesMatchingRecursive("*.patch") }
            ioDispatcher("ImportLibraryFiles").use { dispatcher ->
                importLibraryFiles(
                    patchFiles,
                    devImports.pathOrNull,
                    outputDir.path,
                    libraries.sourcesJars(),
                    index,
                    true,
                    dispatcher,
                )
            }
        }
    }

    private fun importLibraryFiles(
        patches: Iterable<Path>,
        importsFile: Path?,
        targetDir: Path,
        libFiles: List<Path>,
        index: Set<LibraryImport>,
        printOutput: Boolean,
        dispatcher: CoroutineDispatcher,
    ) = runBlocking {
        // Import library classes
        val allImports = findLibraryImports(importsFile, libFiles, index, patches, dispatcher)
        val importsByLib = allImports.groupBy { it.libraryFileName }
        logger.log(if (printOutput) LogLevel.LIFECYCLE else LogLevel.DEBUG, "Importing {} classes from library sources...", allImports.size)

        for ((libraryFileName, imports) in importsByLib) {
            val libFile = libFiles.firstOrNull { it.name == libraryFileName }
                ?: throw PaperweightException("Failed to find library: $libraryFileName for classes ${imports.map { it.importFilePath }}")
            launch(dispatcher) {
                libFile.openZipSafe().use { zipFile ->
                    for (import in imports) {
                        val outputFile = targetDir.resolve(import.importFilePath)
                        if (outputFile.exists()) {
                            continue
                        }
                        outputFile.parent.createDirectories()

                        val libEntry = zipFile.getPath(import.importFilePath)
                        libEntry.copyTo(outputFile)
                    }
                }
            }
        }
    }

    private suspend fun usePatchLines(patches: Iterable<Path>, dispatcher: CoroutineDispatcher, consumer: (String) -> Unit) = coroutineScope {
        for (patch in patches) {
            launch(dispatcher) {
                patch.useLines { lines ->
                    lines.forEach { consumer(it) }
                }
            }
        }
    }

    private suspend fun findLibraryImports(
        libraryImports: Path?,
        libFiles: List<Path>,
        index: Set<LibraryImport>,
        patchFiles: Iterable<Path>,
        dispatcher: CoroutineDispatcher,
    ): Set<LibraryImport> {
        val result = hashSetOf<LibraryImport>()

        // Imports from library-imports.txt
        libraryImports?.useLines { lines ->
            lines.filterNot { it.startsWith('#') }
                .map { it.split(' ') }
                .filter { it.size == 2 }
                .mapTo(result) { parts ->
                    val libFileName = libFiles.firstOrNull { it.name.startsWith(parts[0]) }?.name
                        ?: throw PaperweightException("Failed to read library line '${parts[0]} ${parts[1]}', no library file was found.")
                    LibraryImport(libFileName, parts[1].removeSuffix(".java").replace('.', '/') + ".java")
                }
        }

        // Scan patches for necessary imports
        result += findNeededLibraryImports(patchFiles, index, dispatcher)

        return result
    }

    private suspend fun findNeededLibraryImports(
        patchFiles: Iterable<Path>,
        index: Set<LibraryImport>,
        dispatcher: CoroutineDispatcher,
    ): Set<LibraryImport> {
        val knownImportMap = index.associateBy { it.importFilePath }
        val prefix = "+++ b/"
        val needed = ConcurrentHashMap.newKeySet<LibraryImport>()
        usePatchLines(patchFiles, dispatcher) { line ->
            if (!line.startsWith(prefix)) {
                return@usePatchLines
            }
            val key = line.substring(prefix.length)
            val value = knownImportMap[key]
            if (value != null) {
                needed += value
            }
        }
        return needed
    }
}

private fun FileCollection.sourcesJars(): List<Path> {
    val libFiles = files.map { it.toPath() }.flatMap { it.filesMatchingRecursive("*-sources.jar") }
    if (libFiles.isEmpty()) {
        throw PaperweightException("No library files found")
    }
    return libFiles
}
