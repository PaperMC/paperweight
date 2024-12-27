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

import io.papermc.paperweight.PaperweightException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.*
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

private val openCurrentJvm: MutableMap<Path, ReentrantLock> = ConcurrentHashMap()

fun <R> withLock(
    lockFile: Path,
    printInfoAfter: Long = 1000 * 60 * 5, // 5 minutes
    timeoutMs: Long = 1000L * 60 * 60, // one hour
    action: () -> R,
): R {
    val logger = Logging.getLogger("paperweight lock file")

    var waitedMs = 0L
    var firstFailedAcquire = true
    while (true) {
        val normalized = lockFile.normalize().absolute()

        val lock = openCurrentJvm.computeIfAbsent(normalized) { ReentrantLock() }
        if (!lock.tryLock()) {
            if (firstFailedAcquire) {
                logger.lifecycle("Lock for '$lockFile' is currently held by another thread.")
                logger.lifecycle("Waiting for lock to be released...")
                firstFailedAcquire = false
            }
            val startWait = System.nanoTime()
            val acquired = lock.tryLock(printInfoAfter, TimeUnit.MILLISECONDS)
            if (!acquired) {
                waitedMs += (System.nanoTime() - startWait) / 1_000_000
                if (waitedMs >= timeoutMs) {
                    throw PaperweightException("Have been waiting on lock for '$lockFile' for $waitedMs ms. Giving up as timeout is $timeoutMs ms.")
                }
                logger.lifecycle(
                    "Have been waiting on lock for '$lockFile' for ${waitedMs / 1000 / 60} minute(s).\n" +
                        "If this persists for an unreasonable length of time, kill this process, run './gradlew --stop', and then try again."
                )
            }
        }
        if (openCurrentJvm[normalized] !== lock) {
            lock.unlock()
            continue
        }

        try {
            acquireProcessLockWaiting(lockFile, logger, waitedMs, printInfoAfter, timeoutMs)
            try {
                return action()
            } finally {
                lockFile.deleteForcefully()
            }
        } finally {
            openCurrentJvm.remove(normalized)
            lock.unlock()
        }
    }
}

private fun acquireProcessLockWaiting(
    lockFile: Path,
    logger: Logger,
    alreadyWaited: Long,
    printInfoAfter: Long,
    timeoutMs: Long,
) {
    if (!lockFile.parent.exists()) {
        lockFile.parent.createDirectories()
    }

    val currentPid = ProcessHandle.current().pid()
    var sleptMs: Long = alreadyWaited

    while (true) {
        if (lockFile.exists()) {
            val lockingProcessId = lockFile.readText().toLong()
            if (lockingProcessId == currentPid) {
                throw IllegalStateException("Lock file '$lockFile' is currently held by this process.")
            } else {
                logger.lifecycle("Lock file '$lockFile' is currently held by pid '$lockingProcessId'.")
            }

            if (ProcessHandle.of(lockingProcessId).isEmpty) {
                logger.lifecycle("Locking process does not exist, assuming abrupt termination and deleting lock file.")
                lockFile.deleteIfExists()
            } else {
                logger.lifecycle("Waiting for lock to be released...")
                while (lockFile.exists()) {
                    Thread.sleep(100)
                    sleptMs += 100
                    if (sleptMs >= printInfoAfter && sleptMs % printInfoAfter == 0L) {
                        logger.lifecycle(
                            "Have been waiting on lock file '$lockFile' held by pid '$lockingProcessId' for ${sleptMs / 1000 / 60} minute(s).\n" +
                                "If this persists for an unreasonable length of time, kill this process, run './gradlew --stop', and then" +
                                " try again.\nIf the problem persists, the lock file may need to be deleted manually."
                        )
                    }
                    if (sleptMs >= timeoutMs) {
                        throw PaperweightException(
                            "Have been waiting on lock file '$lockFile' for $sleptMs ms. Giving up as timeout is $timeoutMs ms."
                        )
                    }
                }
            }
        }

        try {
            lockFile.writeText(
                currentPid.toString(),
                options = arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC)
            )
        } catch (e: FileAlreadyExistsException) {
            continue
        }

        break
    }
}
