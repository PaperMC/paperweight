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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.file
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Date

open class ApplyDiffPatches : DefaultTask() {

    @InputFile
    val sourceJar: RegularFileProperty = project.objects.fileProperty()
    @Input
    val sourceBasePath: Property<String> = project.objects.property()
    @InputDirectory
    val patchDir: DirectoryProperty = project.objects.directoryProperty()
    @Input
    val branch: Property<String> = project.objects.property()

    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        val git = Git(outputDir.file)
        git("checkout", "-B", branch.get(), "HEAD").executeSilently(silenceErr = true)

        val uri = URI.create("jar:${sourceJar.file.toURI()}")

        val basePatchDirFile = outputDir.file.resolve("src/main/java")
        val outputDirFile = basePatchDirFile.resolve(sourceBasePath.get())
        outputDirFile.deleteRecursively()

        val patchList = patchDir.file.listFiles() ?: throw PaperweightException("Patch directory does not exist ${patchDir.file}")
        if (patchList.isEmpty()) {
            throw PaperweightException("No patch files found in ${patchDir.file}")
        }

        // Copy in patch targets
        FileSystems.newFileSystem(uri, mapOf<String, Any>()).use { fs ->
            for (file in patchList) {
                val javaName = file.name.replaceAfterLast('.', "java")
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

        git("add", "src").executeOut()
        git("commit", "-m", "Vanilla $ ${Date()}", "--author=Vanilla <auto@mated.null>").executeOut()

        // Apply patches
        for (file in patchList) {
            val javaName = file.name.replaceAfterLast('.', "java")

            println("Patching ${javaName.removeSuffix(".java")}")
            git("apply", "--directory=${basePatchDirFile.relativeTo(outputDir.file).path}", file.absolutePath).executeOut()
        }

        git("add", "src").executeOut()
        git("commit", "-m", "CraftBukkit $ ${Date()}", "--author=CraftBukkit <auto@mated.null>").executeOut()
        git("checkout", "-f", "HEAD~2").executeSilently()
    }
}
