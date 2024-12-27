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

package io.papermc.paperweight.userdev.internal.action

import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging

class CacheCleaner(private val work: Path) {
    companion object {
        private val logger = Logging.getLogger(CacheCleaner::class.java)
    }

    fun cleanCache(deleteUnusedAfter: Long) {
        if (!work.isDirectory()) {
            return
        }

        val start = System.nanoTime()
        var deleted = 0
        var deletedSize = 0L

        work.listDirectoryEntries().forEach {
            val lockFile = it.resolve("lock")
            if (lockFile.exists()) {
                return@forEach
            }
            val metadataFile = it.resolve("metadata.json")
            if (!metadataFile.isRegularFile()) {
                return@forEach
            }
            val since = System.currentTimeMillis() - metadataFile.getLastModifiedTime().toMillis()
            if (since > deleteUnusedAfter) {
                deleted++
                it.deleteRecursive { toDelete ->
                    if (toDelete.isRegularFile()) {
                        deletedSize += Files.size(toDelete)
                    }
                }
            }
        }

        val took = System.nanoTime() - start
        val level = if (deleted > 0) LogLevel.LIFECYCLE else LogLevel.INFO
        logger.log(level, "paperweight-userdev: Deleted $deleted expired cache entries totaling ${deletedSize / 1024}KB in ${took / 1_000_000}ms")
    }
}
