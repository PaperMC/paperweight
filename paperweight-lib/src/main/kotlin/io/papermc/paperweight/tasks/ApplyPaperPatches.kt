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

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
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

    @get:InputFile
    abstract val caseOnlyClassNameChanges: RegularFileProperty

    @get:InputDirectory
    abstract val upstreamDir: DirectoryProperty

    @get:Input
    abstract val upstreamBranch: Property<String>

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val spigotLibrariesDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val unneededFiles: ListProperty<String>

    @get:Input
    abstract val ignoreGitIgnore: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val providers: ProviderFactory

    @get:OutputDirectory
    abstract val mcDevSources: DirectoryProperty

    override fun init() {
        upstreamBranch.convention("master")
        ignoreGitIgnore.convention(Git.ignoreProperty(providers)).finalizeValueOnRead()
    }

    @TaskAction
    fun runLocking() {
        val lockFile = layout.cache.resolve(applyPatchesLock(outputDir.path))
        acquireProcessLockWaiting(lockFile)
        try {
            run()
        } finally {
            lockFile.deleteForcefully()
        }
    }

    private fun run() {
        Git.checkForGit()

        val outputFile = outputDir.path
        recreateCloneDirectory(outputFile)

        val target = outputFile.name

        if (printOutput.get()) {
            println("   Creating $target from remapped source...")
        }

        Git(outputFile).let { git ->
            checkoutRepoFromUpstream(git, upstreamDir.path, upstreamBranch.get())

            val sourceDir = createDir(outputDir.path.resolve("src/main/java"))
            val mcDataDir = outputDir.path.resolve("src/main/resources")
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
            McDev.importMcDev(
                patches = patches,
                decompJar = sourceMcDevJar.path,
                importsFile = devImports.pathOrNull,
                targetDir = sourceDir,
                dataTargetDir = mcDataDir,
                librariesDirs = listOf(spigotLibrariesDir.path, mcLibrariesDir.path),
                printOutput = printOutput.get()
            )

            val caseOnlyChanges = caseOnlyClassNameChanges.path.bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson<List<ClassNameChange>>(reader)
            }

            for (caseOnlyChange in caseOnlyChanges) {
                val obfFile = sourceDir.resolve(caseOnlyChange.obfName + ".java").relativeTo(outputFile)
                val deobfFile = sourceDir.resolve(caseOnlyChange.deobfName + ".java").relativeTo(outputFile)
                git("mv", "-f", obfFile.toString(), deobfFile.toString()).runSilently(silenceErr = true)
            }

            unneededFiles.orNull?.forEach { path -> outputFile.resolve(path).deleteRecursive() }

            git(*Git.add(ignoreGitIgnore, ".")).executeSilently()
            git("commit", "-m", "Initial", "--author=Initial Source <auto@mated.null>").executeSilently()
            git("tag", "-d", "base").runSilently(silenceErr = true)
            git("tag", "base").executeSilently()

            applyGitPatches(git, target, outputFile, patchDir.path, printOutput.get())

            makeMcDevSrc(layout.cache, sourceMcDevJar.path, mcDevSources.path, outputDir.path, sourceDir, mcDataDir)
        }
    }

    private fun createDir(dir: Path): Path {
        dir.deleteRecursive()
        dir.createDirectories()
        return dir
    }
}
