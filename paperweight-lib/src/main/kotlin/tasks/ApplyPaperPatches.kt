/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.McDev
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyPaperPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:InputFile
    abstract val remappedSource: RegularFileProperty

    @get:InputFile
    abstract val remappedTests: RegularFileProperty

    @get:InputDirectory
    abstract val spigotServerDir: DirectoryProperty

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val libraryImports: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val mcdevImports: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        printOutput.convention(true)
    }

    @TaskAction
    fun run() {
        val outputFile = outputDir.path
        outputFile.deleteRecursively()
        outputFile.parent.createDirectories()

        val target = outputFile.name

        if (printOutput.get()) {
            println("   Creating $target from remapped source...")
        }

        Git(outputFile.parent)("clone", spigotServerDir.path.absolutePathString(), outputFile.absolutePathString()).executeSilently()

        Git(outputFile).let { git ->
            // disable gpg for this repo, not needed & slows things down
            git("config", "commit.gpgsign", "false").executeSilently()

            val sourceDir = createDir(outputDir.path.resolve("src/main/java"))
            val testDir = createDir(outputDir.path.resolve("src/test/java"))

            fs.copy {
                from(archives.zipTree(remappedSource.path))
                into(sourceDir)
            }
            fs.copy {
                from(archives.zipTree(remappedTests.path))
                into(testDir)
            }

            val patches = patchDir.path.listDirectoryEntries("*.patch")
            McDev.importMcDev(patches, sourceMcDevJar.path, libraryImports.pathOrNull, mcLibrariesDir.path, mcdevImports.pathOrNull, sourceDir)

            git("add", ".").executeSilently()
            git("commit", "-m", "Initial", "--author=Initial Source <auto@mated.null>").executeSilently()
            git("tag", "base").executeSilently()

            applyGitPatches(git, target, outputFile, patchDir.path, printOutput.get())
        }
    }

    private fun createDir(dir: Path): Path {
        dir.deleteRecursively()
        dir.createDirectories()
        return dir
    }
}
