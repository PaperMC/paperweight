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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.core.util.coreExt
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.URIish
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

    @get:OutputDirectory
    @get:Optional
    abstract val oldOutputDir: DirectoryProperty

    @get:Internal
    abstract val atWorkingDir: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    abstract val libraryImports: DirectoryProperty

    @get:Input
    abstract val identifier: Property<String>

    @get:Internal
    abstract val forkName: Property<String>

    @get:Input
    @get:Optional
    abstract val oldCommit: Property<String>

    override fun init() {
        super.init()
        atWorkingDir.set(layout.cache.resolve(paperTaskOutput(name = "${name}_atWorkingDir")))
        forkName.convention(project.coreExt.activeFork.map { it.name.capitalized() })
    }

    @TaskAction
    fun run() {
        val out = outputDir.path.cleanDir()
        inputDir.path.copyRecursivelyTo(out)

        val git = Git.open(outputDir.path.toFile())

        if (oldCommit.isPresent) {
            setupOld()
        }

        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            println("Applying access transformers...")
            ats.run(
                launcher.get(),
                inputDir.path,
                outputDir.path,
                atFile.path,
                atWorkingDir.path,
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

    private fun setupOld() {
        val name = forkName.get()
        logger.lifecycle("Setting up $name commit ${oldCommit.get()} to use as base for 3-way apply...")

        val rootProjectDir = layout.projectDirectory.dir("../").path
        val oldDir = oldOutputDir.get().path.resolve(oldCommit.get())
        val oldLog = oldOutputDir.get().path.resolve("${oldCommit.get()}.log")

        val oldGit: Git
        if (oldDir.exists()) {
            oldGit = Git.open(oldDir.toFile())
        } else {
            oldDir.createParentDirectories()
            oldGit = Git.init()
                .setDirectory(oldDir.toFile())
                .setInitialBranch("main")
                .call()
            oldGit.remoteRemove().setRemoteName("origin").call()
            oldGit.remoteAdd().setName("origin").setUri(URIish(rootProjectDir.absolutePathString())).call()
        }

        val upstream = Git.open(rootProjectDir.toFile())
        val upstreamConfig = upstream.repository.config
        val upstreamReachableSHA1 = upstreamConfig.getString("uploadpack", null, "allowreachablesha1inwant")
        val upstreamConfigContainsUploadPack = upstreamConfig.sections.contains("uploadpack")
        try {
            // Temporarily allow fetching reachable sha1 refs from the "upstream" repository.
            upstreamConfig.setBoolean("uploadpack", null, "allowreachablesha1inwant", true)
            upstreamConfig.save()
            oldGit.fetch().setDepth(1).setRemote("origin").setRefSpecs(oldCommit.get()).call()
            oldGit.reset().setMode(ResetCommand.ResetType.HARD).setRef(oldCommit.get()).call()
        } finally {
            if (upstreamReachableSHA1 == null) {
                if (upstreamConfigContainsUploadPack) {
                    upstreamConfig.unset("uploadpack", null, "allowreachablesha1inwant")
                } else {
                    upstreamConfig.unsetSection("uploadpack", null)
                }
            } else {
                upstreamConfig.setString("uploadpack", null, "allowreachablesha1inwant", upstreamReachableSHA1)
            }
            upstreamConfig.save()
            upstream.close()
        }

        oldGit.close()

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        oldLog.outputStream().use { logOut ->
            val args = arrayOf(
                "applyAllPatches",
                "--console",
                "plain",
                "--stacktrace",
                "-Dpaperweight.debug=true"
            )
            val command = if (isWindows) {
                listOf("cmd.exe", "/C", "gradlew.bat " + args.joinToString(" "))
            } else {
                listOf("./gradlew", *args)
            }
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(oldDir)
            val process = processBuilder.start()

            val outFuture = redirect(process.inputStream, logOut)
            val errFuture = redirect(process.errorStream, logOut)

            val exit = process.waitFor()
            outFuture.get(500L, TimeUnit.MILLISECONDS)
            errFuture.get(500L, TimeUnit.MILLISECONDS)

            if (exit != 0) {
                throw PaperweightException("Failed to apply old $name, see log at $oldLog")
            }
        }
    }
}
