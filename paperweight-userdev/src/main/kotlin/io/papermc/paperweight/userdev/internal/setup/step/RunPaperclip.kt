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

package io.papermc.paperweight.userdev.internal.setup

import com.google.gson.JsonObject
import io.papermc.paperweight.userdev.internal.setup.step.Input
import io.papermc.paperweight.userdev.internal.setup.step.Output
import io.papermc.paperweight.userdev.internal.setup.step.SetupStep
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.userdev.internal.setup.util.siblingLogFile
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import kotlin.io.path.*

class RunPaperclip(
    @Input private val paperclip: Path,
    @Output private val outputJar: Path,
    @Input private val mojangJar: Path,
    @Input private val minecraftVersion: String,
    private val bundler: Boolean = true,
) : SetupStep {
    override val name: String = "apply mojang mapped paperclip patch"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.ExecutionContext) {
        patchPaperclip(context, paperclip, outputJar, mojangJar, minecraftVersion, bundler)
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.includePaperweightHash = false
    }

    private fun patchPaperclip(
        context: SetupHandler.ExecutionContext,
        paperclip: Path,
        outputJar: Path,
        mojangJar: Path,
        minecraftVersion: String,
        bundler: Boolean,
    ) {
        val logFile = outputJar.siblingLogFile()

        val work = createTempDirectory()
        ensureDeleted(logFile)

        // Copy in mojang jar, so we don't download it twice
        val cache = work.resolve("cache")
        cache.createDirectories()
        mojangJar.copyTo(cache.resolve("mojang_$minecraftVersion.jar"))

        context.javaLauncher.runJar(
            classpath = context.layout.files(paperclip),
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
}
