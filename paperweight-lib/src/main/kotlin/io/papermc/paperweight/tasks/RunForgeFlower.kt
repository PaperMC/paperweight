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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher

val forgeFlowerArgList: List<String> = listOf(
    "-ind=    ", // indentation
    "-din=1", // decompile inner classes
    "-rbr=1", // hide bridge methods
    "-dgs=1", // decompile generic signatures
    "-asc=1", // encode non-ASCII characters in string and character literals as Unicode escapes
    "-rsy=1", //  hide synthetic class members
    "-iec=1", // include entire classpath
    "-jvn=0", // use jad var naming (only difference to mcp config)
    "-isl=0", // inline simple lambdas
    "-iib=1", // ignore invalid bytecode
    "-bsm=1", // bytecode source mapping
    "-dcl=1", // dum code lines
    "-ovr=0", // We add override annotations ourselves. Quiltflower's impl doesn't work as well yet and conflicts
    "-pll=999999", // High line length to effectively disable formatter (only does anything on Quiltflower)
    "-log=TRACE",
    "-cfg",
    "{libraries}",
    "{input}",
    "{output}"
)

private fun List<String>.createForgeFlowerArgs(
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

fun runForgeFlower(
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
        tempFile.bufferedWriter().use { writer ->
            for (lib in libs) {
                if (lib.isLibraryJar) {
                    writer.appendLine("-e=${lib.absolutePathString()}")
                }
            }
        }

        val argList = argsList.createForgeFlowerArgs(
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
