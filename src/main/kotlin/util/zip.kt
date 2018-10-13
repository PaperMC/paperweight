/*
 * Copyright 2018 Kyle Wood
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperweight.util

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

internal fun Task.unzip(zip: Any, target: File? = null): File {
    val input = project.file(zip)

    val outputDir = target ?: input.resolveSibling("${input.name}-" + ThreadLocalRandom.current().nextInt())

    val dirPath = outputDir.toPath()

    val uri = URI.create("jar:${input.toURI()}")
    FileSystems.newFileSystem(uri, mapOf()).use { fs ->
        for (root in fs.rootDirectories) {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(visitDir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(dirPath.resolve(fs.getPath("/").relativize(visitDir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, dirPath.resolve(fs.getPath("/").relativize(file).toString()))
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    return outputDir
}

internal fun Task.zip(inputDir: Any, zip: Any) {
    val outputZipFile = project.file(zip)
    outputZipFile.delete()
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
