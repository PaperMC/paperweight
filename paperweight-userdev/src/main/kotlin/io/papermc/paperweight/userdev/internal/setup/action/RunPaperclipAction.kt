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

package io.papermc.paperweight.userdev.internal.setup.action

import com.google.gson.JsonObject
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.StringValue
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.siblingLogFile
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.jvm.toolchain.JavaLauncher

class RunPaperclipAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    @Input val bundleZip: FileValue,
    @Input val paperclipPath: StringValue,
    @Output val outputJar: FileValue,
    @Input val mojangJar: FileValue,
    @Input val minecraftVersion: StringValue,
    private val bundler: Boolean = true,
) : WorkDispatcher.Action {
    override fun execute() {
        val paperclip = outputJar.get().resolveSibling("paperclip-tmp.jar").cleanFile()
        bundleZip.get().openZipSafe().use { fs ->
            fs.getPath(paperclipPath.get()).copyTo(paperclip)
        }
        try {
            patchPaperclip(paperclip, outputJar.get(), mojangJar.get(), minecraftVersion.get(), bundler)
        } finally {
            paperclip.deleteIfExists()
        }
    }

    private fun patchPaperclip(
        paperclip: Path,
        outputJar: Path,
        mojangJar: Path,
        minecraftVersion: String,
        bundler: Boolean,
    ) {
        val logFile = outputJar.siblingLogFile()

        val work = createTempDirectory(outputJar.parent.createDirectories(), "paperclip")
        ensureDeleted(logFile)

        // Copy in mojang jar, so we don't download it twice
        val cache = work.resolve("cache")
        cache.createDirectories()
        mojangJar.copyTo(cache.resolve("mojang_$minecraftVersion.jar"))

        javaLauncher.get().runJar(
            classpath = listOf(paperclip.toFile()),
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

        ensureDeleted(work)
    }

    private fun handleBundler(paperclip: Path, work: Path, outputJar: Path) {
        paperclip.openZipSafe().use { fs ->
            val root = fs.getPath("/")

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
}
