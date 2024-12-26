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

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.LogLevel
import codechicken.diffpatch.util.archiver.ArchiveFormat
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.mache.macheDecompileJar
import io.papermc.paperweight.userdev.internal.action.DirectoryValue
import io.papermc.paperweight.userdev.internal.action.FileCollectionValue
import io.papermc.paperweight.userdev.internal.action.FileValue
import io.papermc.paperweight.userdev.internal.action.Input
import io.papermc.paperweight.userdev.internal.action.ListValue
import io.papermc.paperweight.userdev.internal.action.Output
import io.papermc.paperweight.userdev.internal.action.Value
import io.papermc.paperweight.userdev.internal.action.WorkDispatcher
import io.papermc.paperweight.userdev.internal.util.jars
import io.papermc.paperweight.util.*
import java.io.PrintStream
import kotlin.io.path.*
import org.gradle.jvm.toolchain.JavaLauncher

class SetupMacheSourcesAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    @Input private val inputJar: FileValue,
    @Output val outputJar: FileValue,
    @Input private val minecraftLibraryJars: DirectoryValue,
    @Input val decompileArgs: ListValue<String>,
    @Input val decompiler: FileCollectionValue,
    @Input val mache: FileCollectionValue,
) : WorkDispatcher.Action {
    override fun execute() {
        val tmpDir = outputJar.get().parent.createDirectories()

        // Decompile
        val tempOut = outputJar.get().resolveSibling("decompile.jar")
        macheDecompileJar(
            tempOut,
            minecraftLibraryJars.get().jars(),
            decompileArgs.get(),
            inputJar.get(),
            javaLauncher.get(),
            decompiler.get(),
            tmpDir,
        )

        // Apply mache patches
        outputJar.get().cleanFile()
        val log = tmpDir.resolve("${outputJar.get().name}.log")
        ensureDeleted(log)
        val result = PrintStream(log.toFile(), Charsets.UTF_8).use { logOut ->
            PatchOperation.builder()
                .logTo(logOut)
                .basePath(tempOut, ArchiveFormat.ZIP)
                .outputPath(outputJar.get(), ArchiveFormat.ZIP)
                .patchesPath(mache.get().singleFile.toPath(), ArchiveFormat.ZIP)
                .patchesPrefix("patches")
                .level(LogLevel.ALL)
                .summary(true)
                .build()
                .operate()
        }
        tempOut.cleanFile()

        if (result.exit != 0) {
            throw PaperweightException("Failed to apply mache patches. See the log file at '${log.toFile()}' for more details.")
        }
    }
}
