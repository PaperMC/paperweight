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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Command
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.UselessOutputStream
import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.findOutputDir
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import io.papermc.paperweight.util.unzip
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyDiffPatches : ControllableOutputTask() {

    @get:InputFile
    abstract val sourceJar: RegularFileProperty

    @get:Input
    abstract val cleanDirPath: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val patchZip: RegularFileProperty

    @get:Input
    abstract val branch: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        printOutput.convention(false)
    }

    @TaskAction
    fun run() {
        val git = Git(outputDir.path)
        git("checkout", "-B", branch.get(), "HEAD").executeSilently(silenceErr = true)

        val basePatchDirFile = outputDir.path.resolve("src/main/java")
        basePatchDirFile.resolve(cleanDirPath.get()).deleteRecursively()

        val patchSource = patchDir.pathOrNull ?: patchZip.path // used for error messages
        val rootPatchDir = patchDir.pathOrNull ?: patchZip.path.let { unzip(it, findOutputDir(it)) }

        try {
            if (!rootPatchDir.isDirectory()) {
                throw PaperweightException("Patch directory does not exist $patchSource")
            }

            val patchList = Files.walk(rootPatchDir).use { it.asSequence().filter { file -> file.isRegularFile() }.toSet() }
            if (patchList.isEmpty()) {
                throw PaperweightException("No patch files found in $patchSource")
            }

            // Copy in patch targets
            sourceJar.path.openZip().use { fs ->
                for (file in patchList) {
                    val javaName = javaFileName(rootPatchDir, file)
                    val out = basePatchDirFile.resolve(javaName)
                    val sourcePath = fs.getPath(javaName)

                    out.parent.createDirectories()
                    sourcePath.copyTo(out)
                }
            }

            git("add", "src").setupOut().execute()
            git("commit", "-m", "Vanilla $ ${Date()}", "--author=Vanilla <auto@mated.null>").setupOut().execute()

            // Apply patches
            for (file in patchList) {
                val javaName = javaFileName(rootPatchDir, file)

                if (printOutput.get()) {
                    println("Patching ${javaName.removeSuffix(".java")}")
                }

                val dirPrefix = basePatchDirFile.relativeTo(outputDir.path).invariantSeparatorsPathString
                git("apply", "--ignore-whitespace", "--directory=$dirPrefix", file.absolutePathString()).setupOut().execute()
            }

            git("add", "src").setupOut().execute()
            git("commit", "-m", "CraftBukkit $ ${Date()}", "--author=CraftBukkit <auto@mated.null>").setupOut().execute()
            git("checkout", "-f", "HEAD~2").setupOut().execute()
        } finally {
            if (rootPatchDir != patchDir.pathOrNull) {
                rootPatchDir.deleteRecursively()
            }
        }
    }

    private fun javaFileName(rootDir: Path, file: Path): String {
        return file.relativeTo(rootDir).toString().replaceAfterLast('.', "java")
    }

    private fun Command.setupOut() = apply {
        if (printOutput.get()) {
            setup(System.out, System.err)
        } else {
            setup(UselessOutputStream, UselessOutputStream)
        }
    }
}
