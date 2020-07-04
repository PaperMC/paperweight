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
import io.papermc.paperweight.util.toProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import java.io.File

open class WriteLibrariesFile : DefaultTask() {

    @Classpath
    val inputFiles: ListProperty<File> = project.objects.listProperty()

    @OutputFile
    val outputFile: RegularFileProperty = project.objects.run {
        fileProperty().convention(project.toProvider(project.cache.resolve(paperTaskOutput("txt"))))
    }

    @TaskAction
    fun run() {
        val files = inputFiles.get()
        files.sort()

        outputFile.file.delete()
        outputFile.file.bufferedWriter().use { writer ->
            for (file in files) {
                writer.appendln("-e=${file.absolutePath}")
            }
        }
    }
}
