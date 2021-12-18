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

package io.papermc.paperweight.userdev.internal.setup

import com.google.gson.JsonObject
import io.papermc.paperweight.userdev.internal.setup.util.buildHashFunction
import io.papermc.paperweight.userdev.internal.setup.util.siblingLogAndHashesFiles
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import kotlin.io.path.*

fun patchPaperclip(
    context: SetupHandler.Context,
    paperclip: Path,
    outputJar: Path,
    mojangJar: Path,
    minecraftVersion: String,
    bundler: Boolean = true,
) {
    val (logFile, hashFile) = outputJar.siblingLogAndHashesFiles()

    val hashFunction = buildHashFunction(paperclip, mojangJar, outputJar, minecraftVersion)
    if (hashFunction.upToDate(hashFile)) {
        return
    }

    UserdevSetup.LOGGER.lifecycle(":applying mojang mapped paperclip patch")

    val work = createTempDirectory()
    logFile.deleteForcefully()

    // Copy in mojang jar, so we don't download it twice
    val cache = work.resolve("cache")
    cache.createDirectories()
    mojangJar.copyTo(cache.resolve("mojang_$minecraftVersion.jar"))

    context.defaultJavaLauncher.runJar(
        classpath = context.project.files(paperclip),
        workingDir = work,
        logFile = logFile,
        jvmArgs = listOf("-Dpaperclip.patchonly=true"),
        args = arrayOf()
    )

    if (bundler) {
        handleBundler(paperclip, work, outputJar)
    } else {
        handleOldPaperclip(work, outputJar)
    }

    work.deleteRecursively()

    hashFunction.writeHash(hashFile)
}

private fun handleBundler(paperclip: Path, work: Path, outputJar: Path) {
    paperclip.openZip().use { fs ->
        val root = fs.rootDirectories.single()

        val serverVersionJson = root.resolve(FileEntry.VERSION_JSON)
        val versionId = gson.fromJson<JsonObject>(serverVersionJson)["id"].asString
        val versions = root.resolve(FileEntry.VERSIONS_LIST).readLines()
            .map { it.split('\t') }
            .associate { it[1] to it[2] }

        val serverJarPath = work.resolve("versions/${versions[versionId]}")
        outputJar.parent.createDirectories()
        serverJarPath.copyTo(outputJar, overwrite = true)
    }
}

private fun handleOldPaperclip(work: Path, outputJar: Path) {
    val patched = work.resolve("cache").listDirectoryEntries()
        .find { it.name.startsWith("patched") } ?: error("Can't find patched jar!")
    patched.copyTo(outputJar, overwrite = true)
}
