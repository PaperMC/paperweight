/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.commitAndTag
import io.papermc.paperweight.tasks.softspoon.ApplySourceATs
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class ForkSetup : JavaLauncherTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @get:Optional
    @get:InputFiles
    abstract val patches: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:Optional
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val out = outputDir.path.ensureClean().createDirectories()
        inputDir.path.copyRecursivelyTo(out)

        val git = Git.open(outputDir.path.toFile())

        if (atFile.isPresent) {
            // TODO - No way to tell JST to ignore the .git dir
            val gitTmp = inputDir.path.resolveSibling(inputDir.path.name + "_.git_tmp")
            inputDir.path.resolve(".git").moveTo(gitTmp)
            try {
                ats.run(
                    launcher.get(),
                    inputDir.path,
                    outputDir.path,
                    atFile.path,
                    temporaryDir.toPath(),
                )
            } finally {
                gitTmp.moveTo(inputDir.path.resolve(".git"))
            }
            commitAndTag(git, "ATs")
        }

        if (!libraries.isEmpty && !patches.isEmpty) {
            val patches = patches.files.flatMap { it.toPath().walk().filter { path -> path.toString().endsWith(".patch") }.toList() }
            McDev.importMcDev(patches, null, devImports.pathOrNull, outputDir.path, null, libraries.files.map { it.toPath() }, true, "")

            commitAndTag(git, "Imports")
        }

        git.close()
    }
}
