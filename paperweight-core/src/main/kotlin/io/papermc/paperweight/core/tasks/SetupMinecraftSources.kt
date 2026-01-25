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

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.LoggingOutputStream
import codechicken.diffpatch.util.archiver.ArchiveFormat
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.URIish
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*

@CacheableTask
abstract class SetupMinecraftSources : JavaLauncherZippedTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Internal
    abstract val predicate: Property<Predicate<Path>>

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraryImports: DirectoryProperty

    @get:Optional
    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    @get:Optional
    @get:Input
    abstract val oldPaperCommit: Property<String>

    @get:Input
    abstract val validateAts: Property<Boolean>

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:Internal
    abstract val atWorkingDir: DirectoryProperty

    override fun init() {
        super.init()
        atWorkingDir.set(layout.cache.resolve(paperTaskOutput(name = "${name}_atWorkingDir")))
    }

    override fun run(outputPath: Path) {
        val git: Git
        if (oldPaperCommit.isPresent) {
            val oldPaperDir = setupOldPaper()

            if (outputPath.exists()) {
                outputPath.deleteRecursive()
            }

            outputPath.createDirectories()

            git = Git.cloneRepository()
                .setDirectory(outputPath.toFile())
                .setRemote("old")
                .setURI(oldPaperDir.resolve("paper-server/src/minecraft/java").absolutePathString())
                .call()
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("ATs").call()

            // Now delete all MC sources so that when we copy in current and commit, it creates an 'update commit'
            outputPath.resolve("com/mojang").deleteRecursive()
            outputPath.resolve("net/minecraft").deleteRecursive()
        } else if (outputPath.resolve(".git/HEAD").isRegularFile()) {
            git = Git.open(outputPath.toFile())
            git.reset().setRef("ROOT").setMode(ResetCommand.ResetType.HARD).call()
        } else {
            outputPath.createDirectories()

            git = Git.init()
                .setDirectory(outputPath.toFile())
                .setInitialBranch("main")
                .call()

            val rootIdent = PersonIdent("ROOT", "noreply+automated@papermc.io")
            git.commit().setMessage("ROOT").setAllowEmpty(true).setAuthor(rootIdent).setSign(false).call()
            git.tagDelete().setTags("ROOT").call()
            git.tag().setName("ROOT").setTagger(rootIdent).setSigned(false).call()
        }

        println("Copy initial sources...")
        inputFile.path.openZip().use { inputFileFs ->
            inputFileFs.walkSequence()
                .filter(predicate.get()::test)
                .forEach {
                    val target = outputPath.resolve(it.toString().substring(1))
                    target.parent.createDirectories()
                    if (it.toString().endsWith(".nbt")) {
                        // nbt files are binary, so we can just copy them
                        it.copyTo(target)
                    } else {
                        // for text files we make sure we have a trailing newline
                        var content = it.readText()
                        if (!content.endsWith("\n")) {
                            content += "\n"
                        }
                        target.writeText(content)
                    }
                }
        }

        println("Setup git repo...")
        if (!oldPaperCommit.isPresent) {
            commitAndTag(git, "Vanilla")
        }

        if (!mache.isEmpty) {
            println("Applying mache patches...")

            val result = PatchOperation.builder()
                .logTo(LoggingOutputStream(logger, LogLevel.LIFECYCLE))
                .basePath(outputPath.convertToPath())
                .outputPath(outputPath.convertToPath())
                .patchesPath(mache.singleFile.toPath(), ArchiveFormat.ZIP)
                .patchesPrefix("patches")
                .level(codechicken.diffpatch.util.LogLevel.INFO)
                .ignorePrefix(".git")
                .build()
                .operate()

            if (!oldPaperCommit.isPresent) {
                commitAndTag(git, "Mache")
            }

            if (result.exit != 0) {
                throw Exception("Failed to apply ${result.summary.failedMatches} mache patches")
            }

            logger.lifecycle("Applied ${result.summary.changedFiles} mache patches")
        }

        if (atFile.isPresent) {
            println("Applying access transformers...")
            ats.run(
                launcher.get(),
                outputPath,
                outputPath,
                atFile.path,
                atWorkingDir.path,
                validate = validateAts.get(),
            )
            if (!oldPaperCommit.isPresent) {
                commitAndTag(git, "ATs", "paper ATs")
            }
        }

        if (oldPaperCommit.isPresent) {
            commitAndTag(git, "Vanilla", "Vanilla, Mache, & paper ATs (Squashed for better Git history during updates)")
        }

        if (libraryImports.isPresent) {
            libraryImports.path.copyRecursivelyTo(outputPath)

            commitAndTag(git, "Imports", "paper Imports")
        }

        git.close()
    }

    private fun setupOldPaper(): Path {
        logger.lifecycle("Setting up Paper commit ${oldPaperCommit.get()} to use as base for constructing Git repo...")

        val rootProjectDir = layout.projectDirectory.dir("../").path
        val oldPaperDir = layout.cache.resolve("$OLD_PAPER_PATH/${oldPaperCommit.get()}")
        val oldPaperLog = layout.cache.resolve("$OLD_PAPER_PATH/${oldPaperCommit.get()}.log")

        val oldPaperGit: Git
        if (oldPaperDir.exists()) {
            oldPaperGit = Git.open(oldPaperDir.toFile())
        } else {
            oldPaperDir.createParentDirectories()
            oldPaperGit = Git.init()
                .setDirectory(oldPaperDir.toFile())
                .setInitialBranch("main")
                .call()
            oldPaperGit.remoteRemove().setRemoteName("origin").call()
            oldPaperGit.remoteAdd().setName("origin").setUri(URIish(rootProjectDir.absolutePathString())).call()
        }

        val upstream = Git.open(rootProjectDir.toFile())
        val upstreamConfig = upstream.repository.config
        val upstreamReachableSHA1 = upstreamConfig.getString("uploadpack", null, "allowreachablesha1inwant")
        val upstreamConfigContainsUploadPack = upstreamConfig.sections.contains("uploadpack")
        try {
            // Temporarily allow fetching reachable sha1 refs from the "upstream" paper repository.
            upstreamConfig.setBoolean("uploadpack", null, "allowreachablesha1inwant", true)
            upstreamConfig.save()
            oldPaperGit.fetch().setDepth(1).setRemote("origin").setRefSpecs(oldPaperCommit.get()).call()
            oldPaperGit.reset().setMode(ResetCommand.ResetType.HARD).setRef(oldPaperCommit.get()).call()
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

        oldPaperGit.close()

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        oldPaperLog.outputStream().use { logOut ->
            val args = arrayOf(
                "applyPatches",
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
            processBuilder.directory(oldPaperDir)
            val process = processBuilder.start()

            val outFuture = redirect(process.inputStream, logOut)
            val errFuture = redirect(process.errorStream, logOut)

            val exit = process.waitFor()
            outFuture.get(500L, TimeUnit.MILLISECONDS)
            errFuture.get(500L, TimeUnit.MILLISECONDS)

            if (exit != 0) {
                throw PaperweightException("Failed to apply old Paper, see log at $oldPaperLog")
            }
        }
        return oldPaperDir
    }
}

fun commitAndTag(git: Git, name: String, message: String = name) {
    val vanillaIdent = PersonIdent(name, "noreply+automated@papermc.io")

    git.add().addFilepattern(".").call()
    git.add().addFilepattern(".").setUpdate(true).call()
    git.commit()
        .setMessage(message)
        .setAuthor(vanillaIdent)
        .setSign(false)
        .setAllowEmpty(true)
        .call()
    git.tagDelete().setTags(name).call()
    git.tag().setName(name).setTagger(vanillaIdent).setSigned(false).call()
}
