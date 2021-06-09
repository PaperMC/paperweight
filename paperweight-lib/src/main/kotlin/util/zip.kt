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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.*

fun unzip(zip: Any, target: Any? = null): Path {
    val input = zip.convertToPath()
    val outputDir = target?.convertToPath()
        ?: input.resolveSibling("${input.name}-" + ThreadLocalRandom.current().nextInt())

    input.openZip().use { fs ->
        fs.walk().use { stream ->
            stream.forEach { p ->
                val targetFile = outputDir.resolve(p.absolutePathString().substring(1))
                targetFile.parent.createDirectories()
                p.copyTo(targetFile)
            }
        }
    }

    return outputDir
}

fun zip(inputDir: Any, zip: Any) {
    val outputZipFile = zip.convertToPath()
    try {
        outputZipFile.deleteIfExists()
    } catch (e: Exception) {
        throw PaperweightException("Could not delete $outputZipFile", e)
    }

    val dirPath = inputDir.convertToPath()

    outputZipFile.writeZip().use { fs ->
        Files.walkFileTree(
            dirPath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != dirPath) {
                        Files.createDirectories(fs.getPath(dirPath.relativize(dir).toString()))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, fs.getPath(dirPath.relativize(file).toString()))
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
}
