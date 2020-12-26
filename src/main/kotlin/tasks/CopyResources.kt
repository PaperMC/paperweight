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

import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.zip
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class CopyResources : BaseTask() {
    @get:InputFile
    abstract val inputJar: RegularFileProperty
    @get:InputFile
    abstract val vanillaJar: RegularFileProperty
    @get:Input
    abstract val includes: ListProperty<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputJar.file
        val target = out.resolveSibling("${out.name}.dir")
        target.mkdirs()

        fs.copy {
            from(archives.zipTree(vanillaJar)) {
                for (inc in this@CopyResources.includes.get()) {
                    include(inc)
                }
            }
            into(target)
            from(archives.zipTree(inputJar))
            into(target)
        }

        zip(target, outputJar)
        target.deleteRecursively()
    }
}
