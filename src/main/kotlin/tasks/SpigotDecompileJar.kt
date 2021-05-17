/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.runJar
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class SpigotDecompileJar : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val fernFlowerJar: RegularFileProperty

    @get:Input
    abstract val decompileCommand: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val inputJarFile = inputJar.path
        val inputJarPath = inputJarFile.absolutePathString()

        val outputJarFile = outputJar.path
        val decomp = outputJarFile.resolveSibling("decomp" + ThreadLocalRandom.current().nextInt())

        try {
            try {
                decomp.createDirectories()
            } catch (e: Exception) {
                throw PaperweightException("Failed to create output directory: $decomp", e)
            }

            val cmd = decompileCommand.get().split(" ").let { it.subList(3, it.size - 2) }.toMutableList()
            cmd += inputJarPath
            cmd += decomp.absolutePathString()

            val logFile = layout.cache.resolve(paperTaskOutput("log"))
            logFile.deleteForcefully()

            runJar(fernFlowerJar, workingDir = layout.cache, logFile = logFile, args = cmd.toTypedArray())

            ensureDeleted(outputJarFile)
            decomp.resolve(inputJarFile.name).moveTo(outputJarFile)
        } finally {
            decomp.deleteRecursively()
        }
    }
}
