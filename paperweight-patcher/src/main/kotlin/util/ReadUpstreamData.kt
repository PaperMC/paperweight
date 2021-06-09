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

package util

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.path
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.notExists
import kotlin.io.path.readLines
import org.gradle.api.file.RegularFileProperty

data class UpstreamData(
    val decompiledJar: Path,
    val libSourceDir: Path
)

fun readUpstreamData(inputFile: RegularFileProperty): UpstreamData {
    val lines = inputFile.path.readLines(Charsets.UTF_8)
    if (lines.size != 2) {
        throw PaperweightException("File has invalid format: ${inputFile.path}")
    }

    val decompiledJar = checkFile(lines[0])
    val libSourceDir = checkFile(lines[1])

    return UpstreamData(decompiledJar, libSourceDir)
}

private fun checkFile(line: String): Path {
    val file = Paths.get(line)
    if (file.notExists()) {
        throw PaperweightException("File does not exist: $file")
    }
    return file
}
