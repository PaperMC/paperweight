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
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

abstract class RebuildPaperPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty
    @get:Console
    abstract val server: Property<Boolean>

    @get:OutputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Internal
    @get:Option(option = "filter-patches", description = "Controls if patches should be cleaned up, defaults to true")
    abstract val filterPatches: Property<Boolean>

    override fun init() {
        printOutput.convention(true)
        filterPatches.convention(true)
        server.convention(false)
    }

    @TaskAction
    fun run() {
        val what = inputDir.file.name
        val patchFolder = patchDir.file
        if (!patchFolder.exists()) {
            patchFolder.mkdirs()
        }

        if (printOutput.get()) {
            println("Formatting patches for $what...")
        }

        if (inputDir.file.resolve(".git/rebase-apply").exists()) {
            // in middle of a rebase, be smarter
            if (printOutput.get()) {
                println("REBASE DETECTED - PARTIAL SAVE")
                val last = inputDir.file.resolve(".git/rebase-apply/last").readText().trim().toInt()
                val next = inputDir.file.resolve(".git/rebase-apply/next").readText().trim().toInt()
                val orderedFiles = patchFolder.listFiles { f -> f.name.endsWith(".patch") }!!
                orderedFiles.sort()

                for (i in 1..last) {
                    if (i < next) {
                        orderedFiles[i].delete()
                    }
                }
            }
        } else {
            patchFolder.deleteRecursively()
            patchFolder.mkdirs()
        }

        Git(inputDir.file)(
            "format-patch",
            "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
            "-o", patchFolder.absolutePath,
            if (server.get()) "base" else "upstream/upstream"
        ).executeSilently()
        val patchDirGit = Git(patchFolder)
        patchDirGit("add", "-A", ".").executeSilently()

        if (filterPatches.get()) {
            cleanupPatches(patchDirGit)
        }

        if (printOutput.get()) {
            println("  Patches saved for $what to ${patchFolder.name}/")
        }
    }

    private fun cleanupPatches(git: Git) {
        val patchFiles = patchDir.file.listFiles { f -> f.name.endsWith(".patch") } ?: emptyArray()
        if (patchFiles.isEmpty()) {
            return
        }
        patchFiles.sortBy { it.name }

        val noChangesPatches = ConcurrentLinkedQueue<File>()
        val futures = mutableListOf<Future<*>>()

        // Calling out to git over and over again for each `git diff --staged` command is really slow from the JVM
        // so to mitigate this we do it parallel
        val executor = Executors.newWorkStealingPool()
        try {
            for (patch in patchFiles) {
                futures += executor.submit {
                    val hasNoChanges = git("diff", "--staged", patch.name).getText().lineSequence()
                        .filter { it.startsWith('+') || it.startsWith('-') }
                        .filterNot { it.startsWith("+++") || it.startsWith("---") }
                        .all { it.startsWith("+index") || it.startsWith("-index") }

                    if (hasNoChanges) {
                        noChangesPatches += patch
                    }
                }
            }

            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        if (noChangesPatches.isNotEmpty()) {
            git("reset", "HEAD", *noChangesPatches.map { it.name }.toTypedArray()).executeSilently()
            git("checkout", "--", *noChangesPatches.map { it.name }.toTypedArray()).executeSilently()
        }

        if (printOutput.get()) {
            for (patch in patchFiles) {
                println(patch.name)
            }
        }
    }
}
