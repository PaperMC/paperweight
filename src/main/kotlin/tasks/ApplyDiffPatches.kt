/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.ext.listFilesRecursively
import io.papermc.paperweight.util.Command
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.UselessOutputStream
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.file
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Date
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyDiffPatches : ControllableOutputTask() {

    @get:InputFile
    abstract val sourceJar: RegularFileProperty

    @get:Input
    abstract val sourceBasePath: Property<String>

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Input
    abstract val branch: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        printOutput.convention(false)
    }

    @TaskAction
    fun run() {
        val git = Git(outputDir.file)
        git("checkout", "-B", branch.get(), "HEAD").executeSilently(silenceErr = true)

        val basePatchDirFile = outputDir.file.resolve("src/main/java")
        val outputDirFile = basePatchDirFile.resolve(sourceBasePath.get())
        outputDirFile.deleteRecursively()

        val patchList = patchDir.file.listFilesRecursively()
            ?.filter { it.isFile }
            ?: throw PaperweightException("Patch directory does not exist ${patchDir.file}")
        if (patchList.isEmpty()) {
            throw PaperweightException("No patch files found in ${patchDir.file}")
        }

        // Copy in patch targets
        val uri = URI.create("jar:" + sourceJar.file.toURI())
        FileSystems.newFileSystem(uri, mapOf<String, Any>()).use { fs ->
            for (file in patchList) {
                val javaName = file.toRelativeString(patchDir.file).replaceAfterLast('.', "java")
                val out = outputDirFile.resolve(javaName)
                val sourcePath = fs.getPath(sourceBasePath.get(), javaName)

                Files.newInputStream(sourcePath).use { input ->
                    ensureParentExists(out)
                    out.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        git("add", "src").setupOut().execute()
        git("commit", "-m", "Vanilla $ ${Date()}", "--author=Vanilla <auto@mated.null>").setupOut().execute()

        // Apply patches
        for (file in patchList) {
            val javaName = file.toRelativeString(patchDir.file).replaceAfterLast('.', "java")

            if (printOutput.get()) {
                println("Patching ${javaName.removeSuffix(".java")}")
            }
            git("apply", "--directory=${basePatchDirFile.relativeTo(outputDir.file).path}", file.absolutePath).setupOut().execute()
        }

        git("add", "src").setupOut().execute()
        git("commit", "-m", "CraftBukkit $ ${Date()}", "--author=CraftBukkit <auto@mated.null>").setupOut().execute()
        git("checkout", "-f", "HEAD~2").setupOut().execute()
    }

    private fun Command.setupOut() = apply {
        if (printOutput.get()) {
            setup(System.out, System.err)
        } else {
            setup(UselessOutputStream, UselessOutputStream)
        }
    }
}
