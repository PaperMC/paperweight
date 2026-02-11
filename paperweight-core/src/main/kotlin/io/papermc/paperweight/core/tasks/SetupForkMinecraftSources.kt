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

import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class SetupForkMinecraftSources : JavaLauncherTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val atWorkingDir: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @get:Input
    abstract val validateATs: Property<Boolean>

    @get:Optional
    @get:InputDirectory
    abstract val libraryImports: DirectoryProperty

    @get:Input
    abstract val identifier: Property<String>

    override fun init() {
        super.init()
        atWorkingDir.set(layout.cache.resolve(paperTaskOutput(name = "${name}_atWorkingDir")))
    }

    @TaskAction
    fun run() {
        val out = outputDir.path.cleanDir()
        inputDir.path.copyRecursivelyTo(out)

        val git = Git.open(outputDir.path.toFile())

        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            println("Applying access transformers...")
            ats.run(
                launcher.get(),
                inputDir.path,
                outputDir.path,
                atFile.path,
                atWorkingDir.path,
                validate = validateATs.get(),
            )
            commitAndTag(git, "ATs", "${identifier.get()} ATs")
        }

        if (libraryImports.isPresent) {
            libraryImports.path.walk().forEach {
                val outFile = out.resolve(it.relativeTo(libraryImports.path).invariantSeparatorsPathString)
                // The file may already exist if upstream imported it
                if (!outFile.exists()) {
                    it.copyTo(outFile.createParentDirectories())
                }
            }

            commitAndTag(git, "Imports", "${identifier.get()} Imports")
        }

        git.close()
    }
}
