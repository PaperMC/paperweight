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

package io.papermc.paperweight.patcher.tasks

import io.papermc.paperweight.tasks.ControllableOutputTask
import io.papermc.paperweight.tasks.applyGitPatches
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.McDev
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import kotlin.io.path.*
import kotlin.io.path.createDirectories
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class SimpleApplyGitPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val libraryImports: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val mcdevImports: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        printOutput.convention(true)
    }

    @TaskAction
    fun run() {
        val output = outputDir.path
        output.deleteRecursively()
        output.parent.createDirectories()

        val target = output.name

        if (printOutput.get()) {
            println("   Creating $target from patch source...")
        }

        Git(output.parent)("clone", sourceDir.path.absolutePathString(), output.absolutePathString()).executeSilently()
        val srcDir = output.resolve("src/main/java")

        val git = Git(output)

        git("config", "commit.gpgsign", "false").executeSilently()

        val patches = patchDir.path.listDirectoryEntries("*.patch")

        if (sourceMcDevJar.isPresent) {
            McDev.importMcDev(patches, sourceMcDevJar.path, libraryImports.pathOrNull, mcLibrariesDir.pathOrNull, mcdevImports.pathOrNull, srcDir)
        }

        git("add", ".").executeSilently()
        git("commit", "--allow-empty", "-m", "--author=Initial Source <auto@mated.null>").executeSilently()
        git("tag", "-d", "base").runSilently(silenceErr = true)
        git("tag", "base").executeSilently()

        applyGitPatches(git, target, output, patchDir.path, printOutput.get())
    }
}
