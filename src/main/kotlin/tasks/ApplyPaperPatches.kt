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
import io.papermc.paperweight.util.McDev
import io.papermc.paperweight.util.file
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyPaperPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty
    @get:InputFile
    abstract val remappedSource: RegularFileProperty
    @get:InputFile
    abstract val templateGitIgnore: RegularFileProperty
    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty
    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty
    @get:InputFile
    abstract val libraryImports: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    @get:OutputDirectory
    abstract val remapTargetDir: DirectoryProperty

    override fun init() {
        printOutput.convention(true)
        remapTargetDir.convention(outputDir.dir("src/main/java"))
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

            fs.copy {
                from(archives.zipTree(remappedSource.file))
                into(sourceDir)
            }

            val patches = patchDir.file.listFiles { _, name -> name.endsWith(".patch") } ?: emptyArray()
            McDev.importMcDev(patches, sourceMcDevJar.file, libraryImports.file, mcLibrariesDir.file, sourceDir)

            templateGitIgnore.file.copyTo(outputFile.resolve(".gitignore"))

            git("add", ".gitignore", ".").executeSilently()
            git("commit", "-m", "Initial", "--author=Initial <auto@mated.null>").executeSilently()

            applyGitPatches(git, target, outputFile, patchDir.file, printOutput.get())
        }
    }
}
