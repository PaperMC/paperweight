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

import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.file
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

open class ApplyPaperPatches : ControllableOutputTask() {

    @InputDirectory
    val patchDir: DirectoryProperty = project.objects.directoryProperty()
    @InputFile
    val remappedSource: RegularFileProperty = project.objects.fileProperty()

    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()
    @OutputDirectory
    val remapTargetDir: DirectoryProperty = project.objects.directoryProperty().convention(outputDir.dir("src/main/java"))

    init {
        printOutput.convention(true)
    }

    @TaskAction
    fun run() {
        val outputFile = outputDir.file
        if (outputFile.exists()) {
            outputFile.deleteRecursively()
        }
        outputFile.mkdirs()

        val target = outputFile.name

        if (printOutput.get()) {
            println("   Creating $target from remapped source...")
        }

        Git(outputFile).let { git ->
            git("init").runSilently(silenceErr = true)

            val sourceDir = remapTargetDir.file
            if (sourceDir.exists()) {
                sourceDir.deleteRecursively()
            }
            sourceDir.mkdirs()

            project.copy {
                from(project.zipTree(remappedSource.file))
                into(sourceDir)
            }

            project.rootProject.file(".gitignore").copyTo(outputFile.resolve(".gitignore"))

            git("add", ".gitignore", ".").executeSilently()
            git("commit", "-m", "Initial", "--author=Initial <auto@mated.null>").executeSilently()

            applyGitPatches(git, target, outputFile, patchDir.file, printOutput.get())
        }
    }
}
