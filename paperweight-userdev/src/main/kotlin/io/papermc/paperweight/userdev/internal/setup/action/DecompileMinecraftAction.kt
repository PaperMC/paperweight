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

import io.papermc.paperweight.userdev.internal.action.*
import io.papermc.paperweight.userdev.internal.util.jars
import io.papermc.paperweight.userdev.internal.util.siblingLogFile
import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.io.path.*
import org.gradle.api.file.FileCollection
import org.gradle.jvm.toolchain.JavaLauncher

class DecompileMinecraftAction(
    @Input private val javaLauncher: Value<JavaLauncher>,
    @Input private val inputJar: FileValue,
    @Output val outputJar: FileValue,
    @Input private val minecraftLibraryJars: DirectoryValue,
    @Input val decompileArgs: ListValue<String>,
    @Input val decompiler: FileCollectionValue,
) : WorkDispatcher.Action {
    override fun execute() {
        runDecompiler(
            argsList = decompileArgs.get(),
            logFile = outputJar.get().siblingLogFile(),
            workingDir = outputJar.get().parent.createDirectories(),
            executable = decompiler.get(),
            inputJar = inputJar.get(),
            libraries = minecraftLibraryJars.get().jars(),
            outputJar = outputJar.get(),
            javaLauncher = javaLauncher.get()
        )
    }

    private fun List<String>.createDecompilerArgs(
        libraries: String,
        input: String,
        output: String,
    ): List<String> = map {
        when (it) {
            "{libraries}" -> libraries
            "{input}" -> input
            "{output}" -> output
            else -> it
        }
    }

    private fun runDecompiler(
        argsList: List<String>,
        logFile: Path,
        workingDir: Path,
        executable: FileCollection,
        inputJar: Path,
        libraries: List<Path>,
        outputJar: Path,
        javaLauncher: JavaLauncher,
        jvmArgs: List<String> = listOf("-Xmx4G")
    ) {
        val libs = ArrayList(libraries)
        libs.sort()
        val tempFile = createTempFile("paperweight", "txt")

        try {
            val vineflower = isVineflower(executable)
            tempFile.bufferedWriter().use { writer ->
                for (lib in libs) {
                    if (lib.isLibraryJar) {
                        if (vineflower) {
                            writer.appendLine("--add-external=${lib.absolutePathString()}")
                        } else {
                            writer.appendLine("-e=${lib.absolutePathString()}")
                        }
                    }
                }
            }

            val argList = argsList.createDecompilerArgs(
                tempFile.absolutePathString(),
                inputJar.absolutePathString(),
                outputJar.absolutePathString(),
            )

            outputJar.deleteForcefully()
            logFile.deleteForcefully()
            outputJar.parent.createDirectories()

            javaLauncher.runJar(executable, workingDir, logFile, jvmArgs = jvmArgs, args = argList.toTypedArray())
        } finally {
            tempFile.deleteForcefully()
        }
    }

    private fun isVineflower(executable: FileCollection) = executable.files.any {
        it.toPath().openZip().use { fs ->
            val manifest = fs.getPath("META-INF/MANIFEST.MF").takeIf { f -> f.isRegularFile() }?.inputStream()?.buffered()?.use { reader ->
                Manifest(reader)
            }
            manifest != null &&
                manifest.mainAttributes.containsKey(Attributes.Name("Implementation-Name")) &&
                manifest.mainAttributes.getValue("Implementation-Name").equals("Vineflower", ignoreCase = true)
        }
    }
}
