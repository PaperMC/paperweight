/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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
import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*

@CacheableTask
abstract class ApplyCraftBukkitPatches : ControllableOutputTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceJar: RegularFileProperty

    @get:Input
    abstract val cleanDirPath: Property<String>

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patchDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patchZip: RegularFileProperty

    @get:Input
    abstract val branch: Property<String>

    @get:Input
    abstract val ignoreGitIgnore: Property<Boolean>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val craftBukkitDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val providers: ProviderFactory

    override fun init() {
        printOutput.convention(false)
        ignoreGitIgnore.convention(Git.ignoreProperty(providers)).finalizeValueOnRead()
        outputDir.convention(project, defaultOutput("repo").path)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        outputDir.path.deleteRecursively()
        outputDir.path.parent.let {
            it.createDirectories()
            val git = Git(it)
            git("clone", "--no-hardlinks", craftBukkitDir.path.absolutePathString(), outputDir.path.absolutePathString()).setupOut().execute()
        }

        val git = Git(outputDir.path)

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

            git(*Git.add(ignoreGitIgnore, "src")).setupOut().execute()
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

            git(*Git.add(ignoreGitIgnore, "src")).setupOut().execute()
            git("commit", "-m", "CraftBukkit $ ${Date()}", "--author=CraftBukkit <auto@mated.null>").setupOut().execute()
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
