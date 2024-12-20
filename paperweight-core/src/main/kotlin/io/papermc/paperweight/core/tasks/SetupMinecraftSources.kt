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
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.softspoon.ApplySourceATs
import io.papermc.paperweight.util.*
import java.nio.file.Path
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

abstract class SetupMinecraftSources : JavaLauncherTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Internal
    abstract val predicate: Property<Predicate<Path>>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val libraryImports: DirectoryProperty

    @get:Optional
    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    @get:Optional
    @get:InputDirectory
    abstract val macheOld: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @TaskAction
    fun run() {
        val outputPath = outputDir.convertToPath()

        val git: Git
        if (outputPath.resolve(".git/HEAD").isRegularFile()) {
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

        if (macheOld.isPresent) {
            println("Using ${macheOld.convertToPath().absolutePathString()} as starting point")
            git.remoteRemove().setRemoteName("old").call()
            git.remoteAdd().setName("old").setUri(URIish(macheOld.convertToPath().absolutePathString())).call()
            git.fetch().setRemote("old").call()
            git.checkout().setName("old/mache").call()
            git.branchDelete().setBranchNames("main").setForce(true).call()
            git.checkout().setName("main").setCreateBranch(true).call()
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
        if (!macheOld.isPresent) {
            // skip this if we are diffing against old, since it would be a commit without mache patches
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

            commitAndTag(git, "Mache")

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
                temporaryDir.toPath(),
            )
            commitAndTag(git, "ATs", "Paper ATs")
        }

        if (libraryImports.isPresent) {
            libraryImports.path.copyRecursivelyTo(outputPath)

            commitAndTag(git, "Imports", "Paper Imports")
        }

        git.close()
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
