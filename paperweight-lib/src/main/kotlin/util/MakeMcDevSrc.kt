/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

import org.gradle.api.logging.Logging
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

fun makeMcDevSrc(decompileJar: Path, paperSource: Path, target: Path) {
    val lock = target.acquireOrWaitForLock()
    try {
        target.listDirectoryEntries()
            .filter { it.name != lock.name }
            .forEach { ensureDeleted(it) }

        decompileJar.openZip().use { fs ->
            val root = fs.getPath("/")
            fs.walk().use { stream ->
                stream.forEach { sourceFile ->
                    if (sourceFile.isRegularFile()) {
                        val sourceFilePath = sourceFile.relativeTo(root).invariantSeparatorsPathString

                        if (!paperSource.resolve(sourceFilePath).isRegularFile()) {
                            val targetFile = target.resolve(sourceFilePath)
                            targetFile.parent.createDirectories()
                            sourceFile.copyTo(targetFile)
                        }
                    }
                }
            }
        }
    } finally {
        lock.deleteForcefully()
    }
}

fun Path.acquireOrWaitForLock(): Path {
    val logger = Logging.getLogger("paperweight mc dev sources lock")
    val lockFile = resolve("paperweight.lock")
    if (lockFile.exists()) {
        val lockingProcessId = lockFile.readText().toLong()
        logger.info("Directory '$this' is currently locked by pid '$lockingProcessId'.")
        val handle = ProcessHandle.of(lockingProcessId)
        if (handle.isEmpty) {
            logger.info("Locking process does not exist, assuming abrupt termination and deleting lock file.")
            lockFile.deleteIfExists()
        } else {
            var sleptMs: Long = 0
            while (lockFile.exists() && handle.isPresent) {
                Thread.sleep(100)
                sleptMs += 100
                if (sleptMs >= 1000 * 60 && sleptMs % (1000 * 60) == 0L) {
                    logger.info(
                        "Have been waiting on lock file '$lockFile' held by pid '$lockingProcessId' for ${sleptMs / 1000 / 60} minute(s).\n" +
                            "If this persists for an unreasonable length of time, kill this process, run './gradlew --stop' and then try again."
                    )
                }
            }
        }
    }

    val pid = ProcessHandle.current().pid()
    if (!exists()) {
        createDirectories()
    }
    lockFile.writeText(pid.toString())
    return lockFile
}
