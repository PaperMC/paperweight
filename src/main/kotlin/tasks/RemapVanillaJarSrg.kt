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

import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.runJar
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.property

open class RemapVanillaJarSrg : DefaultTask() {

    @Input
    val configuration: Property<String> = project.objects.property()
    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val mappings: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val outputJar: RegularFileProperty = defaultOutput()
    @Internal
    val logFile: RegularFileProperty = defaultOutput("log")

    @TaskAction
    fun run() {
        ensureParentExists(outputJar.file)
        ensureDeleted(outputJar.file)

        val mappings = MappingFormats.TSRG.createReader(mappings.file.toPath()).use { it.read() }
        Atlas().apply {
            install { ctx ->
                JarEntryRemappingTransformer(LorenzRemapper(mappings, ctx.inheritanceProvider()))
            }
            run(inputJar.file.toPath(), outputJar.file.toPath())
        }

        /*
        val config = mcpConfig(configFile)
        val cmd = config.functions.getValue("rename")

        val args = cmd.args
        val jvmArgs = cmd.jvmargs ?: listOf()

        val argList = args.map {
            when (it) {
                "{input}" -> inputJar.file.absolutePath
                "{output}" -> outputJar.file.absolutePath
                "{mappings}" -> mappings.file.absolutePath
                else -> it
            }
        }

        val specialSourceJar = project.configurations[configuration.get()].resolve()
            .first { it.name.contains("SpecialSource") }

        runJar(specialSourceJar, project.cache, logFile = logFile.file, jvmArgs = jvmArgs, args = *argList.toTypedArray())
         */
    }
}
