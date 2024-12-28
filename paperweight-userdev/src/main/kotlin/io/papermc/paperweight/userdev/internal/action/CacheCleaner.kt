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

import io.papermc.paperweight.userdev.internal.util.formatNs
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

        val delete = mutableListOf<Path>()
        val keep = mutableListOf<Path>()
        work.listDirectoryEntries().forEach {
            if (it.resolve("lock").exists()) {
                val took = System.nanoTime() - start
                logger.info("paperweight-userdev: Aborted cache cleanup in ${formatNs(took)} due to locked cache entry (${it.name})")
                return
            }
            val metadataFile = it.resolve("metadata.json")
            if (!metadataFile.isRegularFile()) {
                return@forEach
            }
            val since = System.currentTimeMillis() - metadataFile.getLastModifiedTime().toMillis()
            if (since > deleteUnusedAfter) {
                delete.add(it)
            } else {
                keep.add(it)
            }
        }

        var deleted = 0
        var deletedSize = 0L
        if (delete.isNotEmpty()) {
            keep.forEach { k ->
                val metadataFile = k.resolve("metadata.json")
                gson.fromJson<WorkGraph.Metadata>(metadataFile).skippedWhenUpToDate?.let {
                    delete.removeIf { o -> o.name in it }
                }
            }

            delete.forEach {
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
        logger.log(level, "paperweight-userdev: Deleted $deleted expired cache entries totaling ${deletedSize / 1024}KB in ${formatNs(took)}")
    }
}
