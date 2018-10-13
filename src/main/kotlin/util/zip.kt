/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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
import org.gradle.api.Task
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ThreadLocalRandom

fun Task.unzip(zip: Any, target: Any? = null): File {
    val input = project.file(zip)
    val outputDir = target?.let { project.file(it) }
        ?: input.resolveSibling("${input.name}-" + ThreadLocalRandom.current().nextInt())

    project.copy {
        from(project.zipTree(zip))
        into(outputDir)
    }

    return outputDir
}

fun Task.zip(inputDir: Any, zip: Any) {
    val outputZipFile = project.file(zip)
    if (outputZipFile.exists() && !outputZipFile.delete()) {
        throw PaperweightException("Could not delete $outputZipFile")
    }

    val dirPath = project.file(inputDir).toPath()

    val outUri = URI.create("jar:${outputZipFile.toURI()}")
    FileSystems.newFileSystem(outUri, mapOf("create" to "true")).use { fs ->
        Files.walkFileTree(dirPath, object : SimpleFileVisitor<Path>() {
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
        })
    }
}
