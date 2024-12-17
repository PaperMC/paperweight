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
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.util.Arrays
import java.util.stream.Collectors
import java.util.stream.Stream
import java.util.stream.StreamSupport
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileSystemLocationProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider

// utils for dealing with java.nio.file.Path and java.io.File

fun FileSystemLocationProperty<*>.set(path: Path?) = set(path?.toFile())
fun <P : FileSystemLocationProperty<*>> P.pathProvider(path: Provider<Path>) = apply { fileProvider(path.map { it.toFile() }) }

fun DirectoryProperty.convention(project: Project, path: Provider<Path>) = convention(project.layout.dir(path.map { it.toFile() }))
fun RegularFileProperty.convention(project: Project, path: Provider<Path>) = convention(project.layout.file(path.map { it.toFile() }))
fun DirectoryProperty.convention(project: Project, path: Path) = convention(project.layout.dir(project.provider { path.toFile() }))

val Path.isLibraryJar: Boolean
    get() = name.endsWith(".jar") && !name.endsWith("-sources.jar")

fun Path.deleteForcefully() {
    fixWindowsPermissionsForDeletion()
    deleteIfExists()
}

fun Path.deleteRecursive(excludes: Iterable<PathMatcher> = emptyList()) {
    if (!exists()) {
        return
    }
    if (!isDirectory()) {
        if (excludes.any { it.matches(this) }) {
            return
        }
        fixWindowsPermissionsForDeletion()
        deleteIfExists()
        return
    }

    val fileList = Files.walk(this).use { stream ->
        stream.asSequence().filterNot { file -> excludes.any { it.matches(file) } }.toList()
    }

    fileList.forEach { f -> f.fixWindowsPermissionsForDeletion() }
    fileList.asReversed().forEach { f ->
        // Don't try to delete directories where the excludes glob has caused files to not get deleted inside it
        if (f.isRegularFile()) {
            f.deleteIfExists()
        } else if (f.isDirectory() && f.listDirectoryEntries().isEmpty()) {
            f.deleteIfExists()
        }
    }
}

private val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)

private fun Path.fixWindowsPermissionsForDeletion() {
    if (!isWindows || notExists()) {
        return
    }

    runCatching {
        val dosAttr = fileAttributesView<DosFileAttributeView>()
        dosAttr.setHidden(false)
        dosAttr.setReadOnly(false)
    }
}

fun Path.copyRecursivelyTo(target: Path) {
    target.createDirectories()
    if (!exists()) {
        return
    }
    Files.walk(this).use { stream ->
        for (f in stream) {
            val targetPath = target.resolve(f.relativeTo(this).invariantSeparatorsPathString)
            if (f.isDirectory()) {
                targetPath.createDirectories()
            } else {
                f.copyTo(targetPath)
            }
        }
    }
}

fun InputStream.gzip(): GZIPInputStream = GZIPInputStream(this)

fun OutputStream.gzip(): GZIPOutputStream = GZIPOutputStream(this)

inline fun Path.writeZipStream(func: (ZipOutputStream) -> Unit) {
    ZipOutputStream(this.outputStream().buffered()).use(func)
}

inline fun Path.readZipStream(func: (ZipInputStream, ZipEntry) -> Unit) {
    ZipInputStream(this.inputStream().buffered()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            func(zis, entry)
            entry = zis.nextEntry
        }
    }
}

fun copyEntry(input: InputStream, output: ZipOutputStream, entry: ZipEntry) {
    val newEntry = ZipEntry(entry)
    output.putNextEntry(newEntry)
    try {
        input.copyTo(output)
    } finally {
        output.closeEntry()
    }
}

fun ProcessBuilder.directory(path: Path?): ProcessBuilder = directory(path?.toFile())

fun Path.hashFile(algorithm: HashingAlgorithm): ByteArray = inputStream().use { input -> input.hash(algorithm) }

fun Path.sha256asHex(): String = hashFile(HashingAlgorithm.SHA256).asHexString()

fun Path.contentEquals(two: InputStream, bufferSizeBytes: Int = 8192): Boolean {
    inputStream().use { one ->
        val bufOne = ByteArray(bufferSizeBytes)
        val bufTwo = ByteArray(bufferSizeBytes)

        while (true) {
            val readOne = one.read(bufOne)
            val readTwo = two.read(bufTwo)

            if (readOne != readTwo) {
                // length differs
                return false
            }

            if (readOne == -1) {
                // end of content
                break
            }

            if (!Arrays.equals(bufOne, 0, readOne, bufTwo, 0, readOne)) {
                // content differs
                return false
            }
        }
    }

    return true
}

fun Path.contentEquals(file: Path, bufferSizeBytes: Int = 8192): Boolean = file.inputStream().use { two ->
    contentEquals(two, bufferSizeBytes)
}

fun Path.withDifferentExtension(ext: String): Path = resolveSibling("$nameWithoutExtension.$ext")

// Returns true if our process already owns the lock
fun acquireProcessLockWaiting(
    lockFile: Path,
    timeoutMs: Long = 1000L * 60 * 60 // one hour
): Boolean {
    val logger = Logging.getLogger("paperweight lock file")
    val currentPid = ProcessHandle.current().pid()

    if (lockFile.exists()) {
        val lockingProcessId = lockFile.readText().toLong()
        if (lockingProcessId == currentPid) {
            return true
        }

        logger.lifecycle("Lock file '$lockFile' is currently held by pid '$lockingProcessId'.")
        if (ProcessHandle.of(lockingProcessId).isEmpty) {
            logger.lifecycle("Locking process does not exist, assuming abrupt termination and deleting lock file.")
            lockFile.deleteIfExists()
        } else {
            logger.lifecycle("Waiting for lock to be released...")
            var sleptMs: Long = 0
            while (lockFile.exists()) {
                Thread.sleep(100)
                sleptMs += 100
                if (sleptMs >= 1000 * 60 && sleptMs % (1000 * 60) == 0L) {
                    logger.lifecycle(
                        "Have been waiting on lock file '$lockFile' held by pid '$lockingProcessId' for ${sleptMs / 1000 / 60} minute(s).\n" +
                            "If this persists for an unreasonable length of time, kill this process, run './gradlew --stop' and then try again.\n" +
                            "If the problem persists, the lock file may need to be deleted manually."
                    )
                }
                if (sleptMs >= timeoutMs) {
                    throw PaperweightException("Have been waiting on lock file '$lockFile' for $sleptMs ms. Giving up as timeout is $timeoutMs ms.")
                }
            }
        }
    }

    if (!lockFile.parent.exists()) {
        lockFile.parent.createDirectories()
    }
    lockFile.writeText(currentPid.toString())
    return false
}

fun relativeCopy(baseDir: Path, file: Path, outputDir: Path) {
    relativeCopyOrMove(baseDir, file, outputDir, false)
}

fun relativeMove(baseDir: Path, file: Path, outputDir: Path) {
    relativeCopyOrMove(baseDir, file, outputDir, true)
}

private fun relativeCopyOrMove(baseDir: Path, file: Path, outputDir: Path, move: Boolean) {
    val destination = outputDir.resolve(file.relativeTo(baseDir).invariantSeparatorsPathString)
    destination.parent.createDirectories()
    if (move) {
        file.moveTo(destination, overwrite = true)
    } else {
        file.copyTo(destination, overwrite = true)
    }
}

fun Path.createParentDirectories(vararg attributes: FileAttribute<*>): Path = also {
    val parent = it.parent
    if (parent != null && !parent.isDirectory()) {
        try {
            parent.createDirectories(*attributes)
        } catch (e: FileAlreadyExistsException) {
            if (!parent.isDirectory()) throw e
        }
    }
}

val FileSystemLocation.path: Path
    get() = asFile.toPath()
val Provider<out FileSystemLocation>.path: Path
    get() = get().path
val Provider<out FileSystemLocation>.pathOrNull: Path?
    get() = orNull?.path

private fun Path.jarUri(): URI {
    return URI.create("jar:${toUri()}")
}

fun Path.openZip(): FileSystem {
    return try {
        FileSystems.getFileSystem(jarUri())
    } catch (e: FileSystemNotFoundException) {
        FileSystems.newFileSystem(jarUri(), emptyMap<String, Any>())
    }
}

fun Path.writeZip(): FileSystem {
    return FileSystems.newFileSystem(jarUri(), mapOf("create" to "true"))
}

fun FileSystem.walkSequence(vararg options: PathWalkOption): Sequence<Path> {
    return StreamSupport.stream(rootDirectories.spliterator(), false)
        .asSequence()
        .flatMap { it.walk(*options) }
}

fun FileSystem.walk(): Stream<Path> {
    return StreamSupport.stream(rootDirectories.spliterator(), false)
        .flatMap { Files.walk(it) }
}

fun Path.filesMatchingRecursive(glob: String = "*"): List<Path> {
    if (!exists()) {
        return emptyList()
    }
    val matcher = fileSystem.getPathMatcher("glob:$glob")
    return Files.walk(this).use { stream ->
        stream.filter {
            it.isRegularFile() && matcher.matches(it.fileName)
        }.collect(Collectors.toList())
    }
}
