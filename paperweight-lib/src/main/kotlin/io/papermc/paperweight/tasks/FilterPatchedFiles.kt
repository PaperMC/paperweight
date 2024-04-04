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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges

abstract class FilterPatchedFiles : BaseTask() {

    @get:InputDirectory
    @get:Incremental
    abstract val inputSrcDir: DirectoryProperty

    @get:InputDirectory
    @get:Incremental
    abstract val inputResourcesDir: DirectoryProperty

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run(changes: InputChanges) {
        if (!changes.isIncremental) {
            return runFull()
        }
        val srcAdded = changes.added(inputSrcDir)
        val srcRemoved = changes.removed(inputSrcDir)
        val resourceAdded = changes.added(inputResourcesDir)
        val resourceRemoved = changes.removed(inputResourcesDir)
        if (srcAdded.isEmpty() && srcRemoved.isEmpty() && resourceAdded.isEmpty() && resourceRemoved.isEmpty()) {
            logger.info("No adds or removes, not doing work.")
            didWork = false
            return
        }
        vanillaJar.path.openZip().use { vanillaFs ->
            val vanillaRoot = vanillaFs.rootDirectories.single()
            outputJar.path.openZip().use { outputFs ->
                val outputRoot = outputFs.rootDirectories.single()

                for (add in resourceAdded) {
                    if (vanillaRoot.resolve(add).exists()) {
                        outputRoot.resolve(add).deleteIfExists()
                    }
                }
                for (del in resourceRemoved) {
                    val vanilla = vanillaRoot.resolve(del)
                    if (vanilla.exists()) {
                        val out = outputRoot.resolve(del)
                        out.parent.createDirectories()
                        out.deleteIfExists()
                        vanilla.copyTo(out)
                    }
                }

                for (add in srcAdded) {
                    val p = add.removeSuffix(".java") + ".class"
                    val vanilla = vanillaRoot.resolve(p)
                    if (vanilla.exists()) {
                        val outFile = outputRoot.resolve(p)
                        val outDir = outFile.parent
                        val paths = outDir.listDirectoryEntries("${vanilla.name.removeSuffix(".class")}$*.class").toMutableList()
                        paths.add(outFile)
                        paths.forEach { it.deleteIfExists() }
                    }
                }
                for (del in srcRemoved) {
                    val p = del.removeSuffix(".java") + ".class"
                    val vanillaFile = vanillaRoot.resolve(p)
                    if (vanillaFile.exists()) {
                        val paths = vanillaFile.parent.listDirectoryEntries("${vanillaFile.name.removeSuffix(".class")}$*.class").toMutableList()
                        paths.add(vanillaFile)
                        paths.forEach {
                            val out = outputRoot.resolve(it.relativeTo(vanillaRoot))
                            out.parent.createDirectories()
                            out.deleteIfExists()
                            it.copyTo(out)
                        }
                    }
                }
            }
        }
    }

    private fun runFull() {
        val srcFiles = collectFiles(inputSrcDir.path)
        val resourceFiles = collectFiles(inputResourcesDir.path)
        filterJar(
            vanillaJar.path,
            outputJar.path,
            listOf()
        ) {
            if (!it.isRegularFile()) {
                false
            } else if (it.nameCount > 1) {
                val path = it.subpath(0, it.nameCount - 1).resolve(it.fileName.toString().split("$")[0].removeSuffix(".class")).toString()
                !srcFiles.contains("$path.java") && !resourceFiles.contains(path)
            } else {
                true
            }
        }
    }

    private fun collectFiles(dir: Path): Set<String> {
        return Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() }
                .map { it.relativeTo(dir).invariantSeparatorsPathString }
                .collect(Collectors.toUnmodifiableSet())
        }
    }

    private fun InputChanges.added(baseDir: DirectoryProperty): Set<String> {
        return getFileChanges(baseDir).filter { it.changeType == ChangeType.ADDED }
            .map { it.file.toPath().relativeTo(baseDir.path).invariantSeparatorsPathString }
            .toSet()
    }

    private fun InputChanges.removed(baseDir: DirectoryProperty): Set<String> {
        return getFileChanges(baseDir).filter { it.changeType == ChangeType.REMOVED }
            .map { it.file.toPath().relativeTo(baseDir.path).invariantSeparatorsPathString }
            .toSet()
    }
}
