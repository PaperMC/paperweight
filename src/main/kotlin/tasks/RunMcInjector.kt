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

import io.papermc.paperweight.util.McpConfig
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fromJson
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.mcinject
import io.papermc.paperweight.util.runJar
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class RunMcInjector : BaseTask() {

    @get:InputFile
    abstract val configFile: RegularFileProperty
    @get:InputFile
    abstract val executable: RegularFileProperty

    @get:InputFile
    abstract val exceptions: RegularFileProperty
    @get:InputFile
    abstract val access: RegularFileProperty
    @get:InputFile
    abstract val constructors: RegularFileProperty

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty
    @get:Internal
    abstract val logFile: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
        logFile.convention(defaultOutput("log"))
    }

    @TaskAction
    fun run() {
        val config = gson.fromJson<McpConfig>(configFile)

        val argList = config.functions.mcinject.args.map {
            when (it) {
                "{input}" -> inputJar.file.absolutePath
                "{output}" -> outputJar.file.absolutePath
                "{log}" -> logFile.file.absolutePath
                "{exceptions}" -> exceptions.file.absolutePath
                "{access}" -> access.file.absolutePath
                "{constructors}" -> constructors.file.absolutePath
                else -> it
            }
        }

        val jvmArgs = config.functions.mcinject.jvmargs ?: listOf()

        runJar(executable, layout.cache, logFile = null, jvmArgs = jvmArgs, args = *argList.toTypedArray())
    }
}
