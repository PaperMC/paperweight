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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.patches.*
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

@UntrackedTask(because = "Always apply patches")
abstract class ApplyFilePatches : BaseTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val patches: DirectoryProperty

    @get:Internal
    abstract val useNativeDiff: Property<Boolean>

    @get:Internal
    abstract val patchExecutable: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    override fun init() {
        useNativeDiff.convention(false)
        patchExecutable.convention("patch")
    }

    @TaskAction
    open fun run() {
        setup()

        val result = createPatcher().applyPatches(output.convertToPath(), patches.convertToPath(), output.convertToPath(), output.convertToPath())

        commit()

        if (result is PatchFailure) {
            result.failures
                .map { "Patch failed: ${it.patch.relativeTo(patches.get().path)}: ${it.details}" }
                .forEach { logger.error(it) }
            throw Exception("Failed to apply ${result.failures.size} patches")
        }
    }

    open fun setup() {
        io.papermc.paperweight.util.Git.checkForGit()

        val outputPath = output.convertToPath()
        recreateCloneDirectory(outputPath)

        Git(outputPath).let { git ->
            checkoutRepoFromUpstream(git, input.convertToPath(), "main", "mache", "main")
        }

        setupGitHook(outputPath)
    }

    private fun setupGitHook(outputPath: Path) {
        val hook = outputPath.resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
    }

    open fun commit() {
        val ident = PersonIdent("File", "filepatches@automated.papermc.io")
        val git = Git.open(output.convertToPath().toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("File Patches")
            .setAuthor(ident)
            .setSign(false)
            .call()
        git.tagDelete().setTags("file").call()
        git.tag().setName("file").setTagger(ident).setSigned(false).call()
        git.close()
    }

    internal open fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcher(exec, patchExecutable.get())
        } else {
            JavaPatcher()
        }
    }
}
