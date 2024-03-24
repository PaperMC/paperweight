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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.patches.*
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Path
import java.util.function.Predicate
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.io.AccessTransformFormats
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
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

    @get:Internal
    abstract val useNativeDiff: Property<Boolean>

    @get:Internal
    abstract val patchExecutable: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    init {
        run {
            useNativeDiff.convention(false)
            patchExecutable.convention("patch")
        }
    }

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

            val rootIdent = PersonIdent("ROOT", "root@automated.papermc.io")
            git.commit().setMessage("ROOT").setAllowEmpty(true).setAuthor(rootIdent).setSign(false).call()
            git.tagDelete().setTags("ROOT").call()
            git.tag().setName("ROOT").setTagger(rootIdent).setSigned(false).call()
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
        commitAndTag(git, "Vanilla")

        if (machePatches.isPresent) {
            // prepare for patches for patching
            val patchesFolder = machePatches.convertToPath().ensureClean()

            mache.singleFile.toPath().openZip().use { zip ->
                zip.getPath("patches").copyRecursivelyTo(patchesFolder)
            }

            println("Applying mache patches...")
            val result = createPatcher().applyPatches(outputPath, machePatches.convertToPath(), outputPath, outputPath)

            commitAndTag(git, "Mache")

            if (result is PatchFailure) {
                result.failures
                    .map { "Patch failed: ${it.patch.relativeTo(machePatches.get().path)}: ${it.details}" }
                    .forEach { logger.error(it) }
                git.close()
                throw Exception("Failed to apply ${result.failures.size} patches")
            }
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
            McDev.importMcDev(patches, null, devImports.convertToPath(), outputPath, null, libraries.files.map { it.toPath() }, true, "")

            commitAndTag(git, "Imports")
        }

        git.close()
    }

    private fun commitAndTag(git: Git, name: String) {
        val vanillaIdent = PersonIdent(name, "${name.lowercase()}@automated.papermc.io")

        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage(name)
            .setAuthor(vanillaIdent)
            .setSign(false)
            .call()
        git.tagDelete().setTags(name).call()
        git.tag().setName(name).setTagger(vanillaIdent).setSigned(false).call()
    }

    internal open fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcher(exec, patchExecutable.get())
        } else {
            JavaPatcher()
        }
    }
}
