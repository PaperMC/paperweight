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

package io.papermc.paperweight.patcher.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class SimpleApplyGitPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val upstreamDir: DirectoryProperty

    @get:Input
    abstract val upstreamBranch: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Input
    abstract val bareDirectory: Property<Boolean>

    @get:Input
    abstract val importMcDev: Property<Boolean>

    @get:Optional
    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val spigotLibrariesSourceDir: DirectoryProperty

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
        importMcDev.convention(false)
        printOutput.convention(true).finalizeValueOnRead()
        ignoreGitIgnore.convention(Git.ignoreProperty(providers)).finalizeValueOnRead()
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val output = outputDir.path
        recreateCloneDirectory(output)

        val target = output.name

        if (printOutput.get()) {
            println("   Creating $target from patch source...")
        }

        if (bareDirectory.get()) {
            val up = upstreamDir.path
            up.resolve(".git").deleteRecursively()
            Git(up).let { upstreamGit ->
                upstreamGit("init", "--quiet").executeSilently(silenceErr = true)
                upstreamGit("checkout", "-b", upstreamBranch.get()).executeSilently(silenceErr = true)
                upstreamGit.disableAutoGpgSigningInRepo()
                upstreamGit("add", ".").executeSilently(silenceErr = true)
                upstreamGit("commit", "-m", "Initial Source", "--author=Initial <auto@mated.null>").executeSilently(silenceErr = true)
            }
        }

        val git = Git(output)
        checkoutRepoFromUpstream(git, upstreamDir.path, upstreamBranch.get())

        git.disableAutoGpgSigningInRepo()

        val srcDir = output.resolve("src/main/java")

        val patches = patchDir.pathOrNull?.listDirectoryEntries("*.patch") ?: listOf()
        val librarySources = ArrayList<Path>()
        spigotLibrariesSourceDir.pathOrNull?.let { librarySources.add(it) }
        mcLibrariesDir.pathOrNull?.let { librarySources.add(it) }

        if (sourceMcDevJar.isPresent && importMcDev.get()) {
            McDev.importMcDev(
                patches = patches,
                decompJar = sourceMcDevJar.path,
                importsFile = devImports.pathOrNull,
                targetDir = srcDir,
                librariesDirs = librarySources,
                printOutput = printOutput.get()
            )
        }

        git(*Git.add(ignoreGitIgnore, ".")).executeSilently()
        git("commit", "--allow-empty", "-m", "Initial", "--author=Initial Source <auto@mated.null>").executeSilently()
        git("tag", "-d", "base").runSilently(silenceErr = true)
        git("tag", "base").executeSilently()

        applyGitPatches(git, target, output, patchDir.pathOrNull, printOutput.get())

        makeMcDevSrc(sourceMcDevJar.path, srcDir, mcDevSources.path)
    }
}
