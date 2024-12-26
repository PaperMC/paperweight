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

package io.papermc.paperweight.userdev.internal.util

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.Project
import org.gradle.api.provider.Provider

fun formatNs(ns: Long): String {
    val ms = ns / 1_000_000
    if (ms < 1000) {
        return "${ms}ms"
    }
    val s = ms / 1000
    val rem = ms % 1000
    if (s < 60) {
        return "$s.${rem.toString().padStart(3, '0')}s"
    }
    val m = s / 60
    val remS = s % 60 + Math.round(rem / 1000.0)
    return "${m}m ${remS}s"
}

fun Path.siblingLogFile(): Path = withDifferentExtension("log")

fun Path.jars(): List<Path> = filesMatchingRecursive("*.jar")

// set by most CI
val Project.ci: Provider<Boolean>
    get() = providers.environmentVariable("CI")
        .map { it.toBoolean() }
        .orElse(false)

private fun stableProp(name: String) = "paperweight.$name"

private fun experimentalProp(name: String) = "paperweight.experimental.$name"

val Project.genSources: Boolean
    get() {
        val ci = ci.get()
        val prop = providers.gradleProperty(experimentalProp("genSources")).orNull?.toBoolean()
        return prop ?: !ci
    }

val Project.sharedCaches: Boolean
    get() = providers.gradleProperty(stableProp("sharedCaches"))
        .map { it.toBoolean() }
        .orElse(true)
        .get()

fun deleteUnusedAfter(target: Project): Provider<Long> = target.providers.gradleProperty(stableProp("sharedCaches.deleteUnusedAfter"))
    .map { value -> parseDuration(value) }
    .orElse(Duration.ofDays(7))
    .map { duration -> duration.toMillis() }

fun cleanSharedCaches(target: Project, root: Path) {
    if (!root.exists()) {
        return
    }
    val toDelete = Files.walk(root).use { stream ->
        val cutoff: Long by lazy { deleteUnusedAfter(target).get() }
        stream.asSequence()
            .filter { it.name == "last-used.txt" }
            .mapNotNull {
                val pwDir = it.parent.parent // paperweight dir
                val cacheDir = pwDir.parent // cache dir
                val lock = cacheDir.resolve(USERDEV_SETUP_LOCK)
                if (lock.exists() && ProcessHandle.of(lock.readText().toLong()).isPresent) {
                    return@mapNotNull null
                }
                val lastUsed = it.readText().toLong()
                val since = System.currentTimeMillis() - lastUsed
                if (since > cutoff) pwDir else null
            }
            .toList()
    }
    for (path in toDelete) {
        path.deleteRecursive()

        // clean up empty parent directories
        var parent: Path = path.parent
        while (true) {
            val entries = parent.listDirectoryEntries()
            if (entries.isEmpty()) {
                parent.deleteIfExists()
            } else {
                break
            }
            parent = parent.parent
        }
    }
}
