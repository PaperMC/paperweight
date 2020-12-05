/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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
import io.papermc.paperweight.util.McpConfig
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.decompile
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fromJson
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.runJar
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class RunForgeFlower : BaseTask() {

    @get:InputFile
    abstract val configFile: RegularFileProperty
    @get:InputFile
    abstract val executable: RegularFileProperty

    @get:InputFile
    abstract val inputJar: RegularFileProperty
    @get:InputFile
    abstract val libraries: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val out = outputJar.file
        val target = out.resolveSibling("${out.name}.dir")
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()

        val config = gson.fromJson<McpConfig>(configFile)

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
            libraries.file.absolutePath,
            inputJar.file.absolutePath,
            target.absolutePath
        )

        val logFile = layout.cache.resolve(paperTaskOutput("log"))
        logFile.delete()

        val jvmArgs = config.functions.decompile.jvmargs ?: listOf()

        runJar(executable.file, layout.cache, logFile, jvmArgs = jvmArgs, args = *argList.toTypedArray())

        // FernFlower is weird with how it does directory output
        target.resolve(inputJar.file.name).renameTo(out)
        target.deleteRecursively()
    }
}
