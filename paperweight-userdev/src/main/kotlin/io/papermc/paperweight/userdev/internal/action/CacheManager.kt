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
import java.time.Instant
import kotlin.io.path.*
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging

class CacheManager(private val root: Path) {
    companion object {
        private val logger = Logging.getLogger(CacheManager::class.java)
    }

    data class MaintenanceInfo(
        val lastCleanup: Long = System.currentTimeMillis(),
        val scheduledCleanup: Long? = null,
    ) {
        fun writeTo(file: Path) {
            file.createParentDirectories().writeText(gson.toJson(this))
        }

        companion object {
            fun readFrom(file: Path): MaintenanceInfo = gson.fromJson(file)
        }
    }

    fun performMaintenance(
        expireUnusedAfter: Long,
        performCleanupAfter: Long,
        delayCleanupBy: Long,
        bundleZipHash: String,
    ) {
        if (!root.isDirectory()) {
            return
        }

        val maintenanceLock = root.resolve("maintenance.lock")
        val maintenanceFile = root.resolve("maintenance.json")

        val start = System.nanoTime()

        withLock(maintenanceLock) {
            logger.info("paperweight-userdev: Acquired cache maintenance lock in ${formatNs(System.nanoTime() - start)}")

            // update last used for final outputs in case the task was up-to-date
            root.listDirectoryEntries().forEach { entry ->
                val metadataFile = entry.resolve(WorkGraph.METADATA_FILE)
                if (!entry.isDirectory() || !metadataFile.isRegularFile() || entry.resolve("lock").exists()) {
                    return@forEach
                }
                if (entry.name.endsWith("_$bundleZipHash")) {
                    gson.fromJson<WorkGraph.Metadata>(metadataFile).updateLastUsed().writeTo(metadataFile)
                }
            }

            if (maintenanceFile.isRegularFile()) {
                val info = MaintenanceInfo.readFrom(maintenanceFile)
                if (System.currentTimeMillis() - info.lastCleanup < performCleanupAfter) {
                    return@withLock
                }
                if (info.scheduledCleanup == null) {
                    val cleanup = System.currentTimeMillis() + delayCleanupBy
                    logger.info("paperweight-userdev: Scheduled cache cleanup for after ${Instant.ofEpochMilli(cleanup)}")
                    info.copy(scheduledCleanup = cleanup).writeTo(maintenanceFile)
                } else if (System.currentTimeMillis() >= info.scheduledCleanup) {
                    if (cleanup(expireUnusedAfter)) {
                        info.copy(lastCleanup = System.currentTimeMillis(), scheduledCleanup = null).writeTo(maintenanceFile)
                    }
                    // else: cleanup was skipped due to locked cache entry, try again later
                }
            } else {
                MaintenanceInfo().writeTo(maintenanceFile)
            }
        }

        logger.info("paperweight-userdev: Finished cache maintenance in ${formatNs(System.nanoTime() - start)}")
    }

    private fun cleanup(deleteUnusedAfter: Long): Boolean {
        val tryDelete = mutableListOf<Path>()
        val keep = mutableListOf<Path>()
        root.listDirectoryEntries().forEach {
            val metadataFile = it.resolve(WorkGraph.METADATA_FILE)
            if (!metadataFile.isRegularFile()) {
                return@forEach
            }
            if (it.resolve("lock").exists()) {
                logger.info("paperweight-userdev: Aborted cache cleanup due to locked cache entry (${it.name})")
                return false
            }
            val since = System.currentTimeMillis() - metadataFile.getLastModifiedTime().toMillis()
            if (since > deleteUnusedAfter) {
                tryDelete.add(it)
            } else {
                keep.add(it)
            }
        }

        var deleted = 0
        var deletedSize = 0L
        if (tryDelete.isNotEmpty()) {
            keep.forEach { k ->
                val metadataFile = k.resolve(WorkGraph.METADATA_FILE)
                gson.fromJson<WorkGraph.Metadata>(metadataFile).skippedWhenUpToDate?.let {
                    tryDelete.removeIf { o -> o.name in it }
                }
            }

            tryDelete.forEach {
                deleted++
                it.deleteRecursive { toDelete ->
                    if (toDelete.isRegularFile()) {
                        deletedSize += Files.size(toDelete)
                    }
                }
            }
        }

        val level = if (deleted > 0) LogLevel.LIFECYCLE else LogLevel.INFO
        logger.log(
            level,
            "paperweight-userdev: Deleted $deleted expired cache entries totaling ${deletedSize / 1024}KB"
        )
        return true
    }
}
