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

package io.papermc.paperweight.userdev.internal.setup.util

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.userdev.PaperweightUser
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.stream.Collectors
import kotlin.io.path.*
import kotlin.streams.asSequence
import kotlin.system.measureTimeMillis
import org.gradle.api.Project
import org.gradle.api.provider.Provider

val paperweightHash: String by lazy { hashPaperweightJar() }

fun Path.siblingLogFile(): Path = withDifferentExtension("log")

fun Path.siblingHashesFile(): Path = withDifferentExtension("hashes")

fun Path.siblingLogAndHashesFiles() = Pair(siblingLogFile(), siblingHashesFile())

fun hash(vararg things: Any): String = hash(things.toList())

fun hash(things: List<Any>): String {
    val strings = arrayListOf<String>()
    val paths = arrayListOf<Path>()

    for (thing in things) {
        when (thing) {
            is String -> strings.add(thing)
            is Path -> paths.add(thing)
            is Iterable<*> -> strings.add(hash(thing.filterNotNull()))
            else -> error("Unknown type: ${thing.javaClass.name}")
        }
    }

    return hashFiles(paths) + if (strings.isEmpty()) {
        ""
    } else {
        "\n" + strings.sorted().joinToString("\n") { it.hash(HashingAlgorithm.SHA256).asHexString() }
    }
}

fun hashFiles(files: List<Path>): String = files.asSequence()
    .filter { it.isRegularFile() }
    .sortedBy { it.pathString }
    .joinToString("\n") {
        "${it.fileName.pathString}:${it.sha256asHex()}"
    }

fun hashDirectory(dir: Path): String = Files.walk(dir).use { stream -> hashFiles(stream.filter { it.isRegularFile() }.collect(Collectors.toList())) }

fun DownloadService.download(
    downloadName: String,
    remote: String,
    destination: Path,
    forceDownload: Boolean = false,
    expectedHash: Hash? = null
): DownloadResult<Unit> {
    val hashFile = destination.siblingHashesFile()

    val toHash = mutableListOf<Any>(remote, destination)
    expectedHash?.let { toHash += it.valueLower }
    val upToDate = !forceDownload &&
        hashFile.isRegularFile() &&
        hashFile.readText() == hash(toHash)
    if (upToDate) {
        return DownloadResult(destination, false, Unit)
    }

    UserdevSetup.LOGGER.lifecycle(":executing 'download {}'", downloadName)
    val elapsed = measureTimeMillis {
        destination.parent.createDirectories()
        destination.deleteIfExists()
        download(remote, destination, expectedHash)
    }
    UserdevSetup.LOGGER.info("done executing 'download {}', took {}s", downloadName, elapsed / 1000.00)
    hashFile.writeText(hash(toHash))

    return DownloadResult(destination, true, Unit)
}

fun buildHashFunction(vararg things: Any, op: HashFunctionBuilder.() -> Unit = {}): HashFunction = HashFunction {
    val builder = HashFunctionBuilder.create()
    builder.op()
    if (builder.includePaperweightHash) {
        builder.include(paperweightHash)
    }
    builder.include(*things)

    hash(builder)
}

data class DownloadResult<D>(val path: Path, val didDownload: Boolean, val data: D) {
    fun <N> mapData(mapper: (DownloadResult<D>) -> N): DownloadResult<N> = DownloadResult(path, didDownload, mapper(this))
}

interface HashFunctionBuilder : MutableList<Any> {
    var includePaperweightHash: Boolean

    fun include(vararg things: Any): Boolean = addAll(things.toList())

    fun include(thing: Any): Boolean = add(thing)

    companion object {
        fun create(): HashFunctionBuilder = HashFunctionBuilderImpl()
    }

    private class HashFunctionBuilderImpl(
        override var includePaperweightHash: Boolean = true,
    ) : HashFunctionBuilder, MutableList<Any> by ArrayList()
}

fun interface HashFunction : () -> String {
    fun writeHash(hashFile: Path) {
        hashFile.parent.createDirectories()
        hashFile.writeText(this())
    }

    fun upToDate(hashFile: Path): Boolean {
        return hashFile.isRegularFile() && hashFile.readText() == this()
    }
}

private fun hashPaperweightJar(): String {
    val userdevShadowJar = Paths.get(PaperweightUser::class.java.protectionDomain.codeSource.location.toURI())
    return userdevShadowJar.sha256asHex()
}

fun <R> lockSetup(cache: Path, canBeNested: Boolean = false, action: () -> R): R {
    val lockFile = cache.resolve(USERDEV_SETUP_LOCK)
    val alreadyHad = acquireProcessLockWaiting(lockFile)
    try {
        return action()
    } finally {
        if (!canBeNested || !alreadyHad) {
            lockFile.deleteForcefully()
        }
    }
}

// set by most CI
val Project.ci: Provider<Boolean>
    get() = providers.environmentVariable("CI")
        .map { it.toBoolean() }
        .orElse(false)

private fun experimentalProp(name: String) = "paperweight.experimental.$name"

val Project.genSources: Boolean
    get() {
        val ci = ci.get()
        val prop = providers.gradleProperty(experimentalProp("genSources")).orNull?.toBoolean()
        return prop ?: !ci
    }

val Project.sharedCaches: Boolean
    get() = providers.gradleProperty(experimentalProp("sharedCaches")).orNull.toBoolean()

private fun deleteUnusedAfter(target: Project): Long = target.providers.gradleProperty(experimentalProp("sharedCaches.deleteUnusedAfter"))
    .map { value -> parseDuration(value) }
    .orElse(Duration.ofDays(7))
    .map { duration -> duration.toMillis() }
    .get()

fun lastUsedFile(cacheDir: Path): Path = cacheDir.resolve(paperSetupOutput("last-used", "txt"))

fun cleanSharedCaches(target: Project, root: Path) {
    if (!root.exists()) {
        return
    }
    val toDelete = Files.walk(root).use { stream ->
        stream.asSequence()
            .filter { it.name == "last-used.txt" }
            .mapNotNull {
                val dir = it.parent.parent // paperweight dir
                val lastUsed = it.readText().toLong()
                val since = System.currentTimeMillis() - lastUsed
                val cutoff = deleteUnusedAfter(target)
                if (since > cutoff) dir else null
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
