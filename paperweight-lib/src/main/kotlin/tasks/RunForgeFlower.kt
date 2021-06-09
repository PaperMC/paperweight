/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.isLibraryJar
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.runJar
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class RunForgeFlower : BaseTask() {

    @get:InputFile
    abstract val executable: RegularFileProperty

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:Classpath
    abstract val libraries: DirectoryProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val out = outputJar.path
        val target = out.resolveSibling("${out.name}.dir")
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.createDirectories()

        val libs = libraries.path.useDirectoryEntries { it.toMutableList() }
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

            val argList = listOf(
                "-ind=    ",
                "-din=1",
                "-rbr=1",
                "-dgs=1",
                "-asc=1",
                "-rsy=1",
                "-iec=1",
                "-jvn=0",
                "-isl=0",
                "-iib=1",
                "-log=TRACE",
                "-cfg",
                tempFile.absolutePathString(),
                inputJar.path.absolutePathString(),
                target.absolutePathString()
            )

            val logFile = layout.cache.resolve(paperTaskOutput("log"))
            logFile.deleteForcefully()

            val jvmArgs = listOf("-Xmx4G")

            runJar(executable.path, layout.cache, logFile, jvmArgs = jvmArgs, args = argList.toTypedArray())

            // FernFlower is weird with how it does directory output
            target.resolve(inputJar.path.name).moveTo(out, overwrite = true)
            target.deleteRecursively()
        } finally {
            tempFile.deleteForcefully()
        }
    }
}
