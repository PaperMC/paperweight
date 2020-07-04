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

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.property

open class Extract : DefaultTask() {

    @Input
    val config: Property<String> = project.objects.property()

    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        project.copy {
            from(project.zipTree(project.configurations[config.get()].resolve().single()))
            into(outputDir)
        }
    }
}

open class ExtractMcpMappings : Extract() {

    @OutputFile
    val fieldsCsv: RegularFileProperty = project.objects.fileProperty()
        .convention(outputDir.map { it.file("fields.csv") })
    @OutputFile
    val methodsCsv: RegularFileProperty = project.objects.fileProperty()
        .convention(outputDir.map { it.file("methods.csv") })
    @OutputFile
    val paramsCsv: RegularFileProperty = project.objects.fileProperty()
        .convention(outputDir.map { it.file("params.csv") })
}

open class ExtractMcpData : Extract() {

    @OutputFile
    val configJson: RegularFileProperty = project.objects.fileProperty()
        .convention(outputDir.map { it.file("config.json") })
}
