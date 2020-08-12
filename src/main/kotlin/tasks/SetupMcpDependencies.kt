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

import io.papermc.paperweight.util.mcpConfig
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

open class SetupMcpDependencies : DefaultTask() {

    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()
    @Input
    val forgeFlowerConfig: Property<String> = project.objects.property()
    @Input
    val mcInjectorConfig: Property<String> = project.objects.property()
    @Input
    val specialSourceConfig: Property<String> = project.objects.property()

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val config = mcpConfig(configFile)

        project.dependencies.add(forgeFlowerConfig.get(), config.functions.getValue("decompile").version)
        project.dependencies.add(mcInjectorConfig.get(), config.functions.getValue("mcinject").version)
        project.dependencies.add(specialSourceConfig.get(), config.functions.getValue("rename").version)
    }
}
