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

package io.papermc.paperweight.util.data

import java.nio.file.Path
import kotlin.io.path.*

data class FileEntry<T>(
    val hash: String,
    val id: T,
    val path: String
) {
    override fun toString(): String {
        return "$hash\t$id\t$path"
    }

    companion object {
        const val VERSION_JSON = "version.json"
        const val VERSIONS_DIR = "META-INF/versions"
        const val LIBRARIES_DIR = "META-INF/libraries"
        const val VERSIONS_LIST = "META-INF/versions.list"
        const val LIBRARIES_LIST = "META-INF/libraries.list"

        fun parse(file: Path): List<FileEntry<String>> {
            return parse(file) { it }
        }

        fun <T> parse(file: Path, transform: (String) -> T): List<FileEntry<T>> {
            return file.readLines().mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) {
                    return@mapNotNull null
                }

                val (hash, id, path) = line.split("\t")
                FileEntry(hash, transform(id), path)
            }
        }
    }
}
