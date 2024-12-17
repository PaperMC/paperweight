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

import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

private val openJars: MutableMap<Path, FileSystemReferenceImpl> = ConcurrentHashMap()

interface FileSystemReference : AutoCloseable {
    fun getPath(path: String): Path

    fun walkSequence(vararg options: PathWalkOption): Sequence<Path>

    fun getPathMatcher(matcher: String): PathMatcher
}

private class FileSystemReferenceImpl(
    private val path: Path,
    private val reference: FileSystem,
    @Volatile
    var openers: Int = 0,
    @Volatile
    var closed: Boolean = false,
) : FileSystemReference {
    override fun getPath(path: String): Path = reference.getPath(path)

    override fun walkSequence(vararg options: PathWalkOption): Sequence<Path> = reference.walkSequence()

    override fun getPathMatcher(matcher: String): PathMatcher = reference.getPathMatcher(matcher)

    override fun close() {
        synchronized(this) {
            openers--
            if (openers == 0) {
                try {
                    reference.close()
                } finally {
                    closed = true
                    openJars.remove(path)
                }
            }
        }
    }
}

/**
 * Variant of [openZip] that is safe to use for opening the same jar
 * from multiple threads.
 *
 * @return file system reference
 */
fun Path.openZipSafe(): FileSystemReference {
    while (true) {
        val reference = openJars.computeIfAbsent(normalize().absolute()) {
            FileSystemReferenceImpl(it, it.openZip())
        }
        synchronized(reference) {
            if (!reference.closed) {
                reference.openers++
                return reference
            }
        }
        // FS closed and removed from map, retry
    }
}
