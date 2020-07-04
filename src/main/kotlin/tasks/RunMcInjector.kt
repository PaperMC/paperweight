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

import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.mcpFile
import io.papermc.paperweight.util.runJar
import io.papermc.paperweight.util.toProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.property

open class RunMcInjector : DefaultTask() {

    @Input
    val configuration: Property<String> = project.objects.property()
    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val outputJar: RegularFileProperty = project.objects.run {
        fileProperty().convention(project.toProvider(project.cache.resolve(paperTaskOutput())))
    }
    @OutputFile
    val logFile: RegularFileProperty = project.objects.run {
        fileProperty().convention(project.toProvider(project.cache.resolve(paperTaskOutput("log"))))
    }

    @TaskAction
    fun run() {
        val config = mcpConfig(configFile)
        val exceptions = mcpFile(configFile, config.data.exceptions)
        val access = mcpFile(configFile, config.data.access)
        val constructors = mcpFile(configFile, config.data.constructors)

        val mcInjectorArgs = config.functions.getValue("mcinject").args
        val jvmArgs = config.functions.getValue("mcinject").jvmargs ?: listOf()

        val argList = mcInjectorArgs.map {
            when (it) {
                "{input}" -> inputJar.file.absolutePath
                "{output}" -> outputJar.file.absolutePath
                "{log}" -> logFile.file.absolutePath
                "{exceptions}" -> exceptions.absolutePath
                "{access}" -> access.absolutePath
                "{constructors}" -> constructors.absolutePath
                else -> it
            }
        }

        val mcInjectorJar = project.configurations[configuration.get()].resolve().single()

        runJar(mcInjectorJar, project.cache, logFile = null, jvmArgs = jvmArgs, args = *argList.toTypedArray())
    }
}
