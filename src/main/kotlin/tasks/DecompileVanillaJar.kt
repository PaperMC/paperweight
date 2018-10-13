/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.runJar
import io.papermc.paperweight.util.toProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.util.concurrent.ThreadLocalRandom

open class DecompileVanillaJar : DefaultTask() {

    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val fernFlowerJar: RegularFileProperty = project.objects.fileProperty()
    @Input
    val decompileCommand: Property<String> = project.objects.property()

    @OutputFile
    val outputJar: RegularFileProperty = project.objects.run {
        fileProperty().convention(project.toProvider(project.cache.resolve(paperTaskOutput())))
    }

    @TaskAction
    fun run() {
        val inputJarFile = inputJar.asFile.get()
        val inputJarPath = inputJarFile.canonicalPath

        val outputJarFile = outputJar.asFile.get()
        val decomp = outputJarFile.resolveSibling("decomp" + ThreadLocalRandom.current().nextInt())

        try {
            if (!decomp.exists() && !decomp.mkdirs()) {
                throw PaperweightException("Failed to create output directory: $decomp")
            }

            val cmd = decompileCommand.get().split(" ").let { it.subList(3, it.size - 2) }.toMutableList()
            cmd += inputJarPath
            cmd += decomp.canonicalPath

            val logFile = project.cache.resolve(paperTaskOutput("log"))
            logFile.delete()

            runJar(fernFlowerJar, workingDir = project.cache, logFile = logFile, args = *cmd.toTypedArray())

            ensureDeleted(outputJarFile)
            decomp.resolve(inputJarFile.name).renameTo(outputJarFile)
        } finally {
            decomp.deleteRecursively()
        }
    }
}
