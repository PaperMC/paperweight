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

package io.papermc.paperweight.tasks.mache

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.LoggingOutputStream
import codechicken.diffpatch.util.archiver.ArchiveFormat
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Path
import java.util.function.Predicate
import kotlin.io.path.*
import org.cadixdev.at.io.AccessTransformFormats
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
import org.openrewrite.InMemoryExecutionContext

abstract class SetupVanilla : BaseTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Internal
    abstract val predicate: Property<Predicate<Path>>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val machePatches: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ats: RegularFileProperty

    @get:Optional
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:Optional
    @get:InputFiles
    abstract val paperPatches: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:Optional
    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Optional
    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    @get:Optional
    @get:InputDirectory
    abstract val macheOld: DirectoryProperty

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
        inputFile.convertToPath().openZip().walk()
            .filter(predicate.get())
            .forEach {
                val target = outputPath.resolve(it.toString().substring(1))
                target.parent.createDirectories()
                // make sure we have a trailing newline
                var content = it.readText()
                if (!content.endsWith("\n")) {
                    content += "\n"
                }
                target.writeText(content)
            }

        println("Setup git repo...")
        if (!macheOld.isPresent) {
            // skip this if we are diffing against old, since it would be a commit without mache patches
            commitAndTag(git, "Vanilla")
        }

        if (machePatches.isPresent) {
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

        if (ats.isPresent) {
            val classPath = minecraftClasspath.files.map { it.toPath() }.toMutableList()
            classPath.add(outputPath)

            println("Applying access transformers...")
            val configuration = RestampContextConfiguration.builder()
                .accessTransformers(ats.convertToPath(), AccessTransformFormats.FML)
                .sourceRoot(outputPath)
                .sourceFilesFromAccessTransformers(false)
                .classpath(classPath)
                .executionContext(InMemoryExecutionContext { it.printStackTrace() })
                .failWithNotApplicableAccessTransformers()
                .build()

            val parsedInput = RestampInput.parseFrom(configuration)
            val results = Restamp.run(parsedInput).allResults

            results.forEach { result ->
                if (result.after != null) {
                    outputPath.resolve(result.after!!.sourcePath).writeText(result.after!!.printAll())
                }
            }

            commitAndTag(git, "ATs")
        }

        if (!libraries.isEmpty && !paperPatches.isEmpty) {
            val patches = paperPatches.files.flatMap { it.toPath().walk().filter { path -> path.toString().endsWith(".patch") }.toList() }
            McDev.importMcDev(patches, null, devImports.pathOrNull, outputPath, null, libraries.files.map { it.toPath() }, true, "")

            commitAndTag(git, "Imports")
        }

        git.close()
    }

    private fun commitAndTag(git: Git, name: String) {
        val vanillaIdent = PersonIdent(name, "noreply+automated@papermc.io")

        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage(name)
            .setAuthor(vanillaIdent)
            .setSign(false)
            .call()
        git.tagDelete().setTags(name).call()
        git.tag().setName(name).setTagger(vanillaIdent).setSigned(false).call()
    }
}