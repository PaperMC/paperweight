/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Git
import java.io.File

class PatchApplier(
    private val remappedBranch: String,
    private val unmappedBranch: String,
    targetDir: File
) {

    private val git = Git(targetDir)

    private var commitMessage: String? = null
    private var commitAuthor: String? = null
    private var commitTime: String? = null

    private val remappedBaseTag: String = "remapped-base"

    fun checkoutRemapped() {
        println("Switching to $remappedBranch without losing changes")
        git("symbolic-ref", "HEAD", "refs/heads/$remappedBranch").executeSilently()
    }

    fun checkoutOld() {
        println("Resetting back to $unmappedBranch branch")
        git("checkout", unmappedBranch).executeSilently()
    }

    fun commitInitialSource() {
        git("checkout", "-b", unmappedBranch).executeSilently()
        git("add", ".").executeSilently()
        git("commit", "-m", "Initial Source", "--author=Initial <auto@mated.null>").executeSilently()
        git("branch", remappedBranch).executeSilently()
    }

    fun commitInitialRemappedSource() {
        git("add", ".").executeSilently()
        git("commit", "-m", "Initial Remapped Source", "--author=Initial <auto@mated.null>").executeSilently()
        git("tag", remappedBaseTag)
    }

//    fun commitRemappingDifferences(remapper: PatchSourceRemapWorker) {
//        checkoutRemapped() // Switch to remapped branch without checkout out files
//        remapper.remap() // Remap to new mappings
//        println("Committing remap")
//        git("add", ".").executeSilently()
//        git("commit", "-m", "Remap", "--author=Initial <auto@mated.null>").executeSilently()
//        checkoutOld()
//    }

    fun recordCommit() {
        commitMessage = git("log", "--format=%B", "-n", "1", "HEAD").getText()
        commitAuthor = git("log", "--format=%an <%ae>", "-n", "1", "HEAD").getText()
        commitTime = git("log", "--format=%aD", "-n", "1", "HEAD").getText()
    }

    fun commitChanges() {
        println("Committing remapped changes to $remappedBranch")
        val message = commitMessage ?: throw PaperweightException("commitMessage not set")
        val author = commitAuthor ?: throw PaperweightException("commitAuthor not set")
        val time = commitTime ?: throw PaperweightException("commitTime not set")
        commitMessage = null
        commitAuthor = null
        commitTime = null

        git("add", ".").executeSilently()
        git("commit", "-m", message, "--author=$author", "--date=$time").execute()
    }

    fun applyPatch(patch: File) {
        println("Applying patch ${patch.name}")
        val result = git("am", "--3way", "--ignore-whitespace", patch.absolutePath).runOut()
        if (result != 0) {
            System.err.println("Patch failed to apply: $patch")
            throw RuntimeException("Patch failed to apply: $patch")
        }
    }

    fun generatePatches(target: File) {
        target.deleteRecursively()
        target.mkdirs()
        git("checkout", remappedBranch).executeSilently()
        git(
            "format-patch", "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N", "-o",
            target.absolutePath, remappedBaseTag
        ).runOut()
    }
}
