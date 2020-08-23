package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.shared.PaperweightException
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

    fun initRepo() {
        println("Initializing patch remap repo")
        git("init").executeSilently()
        git("commit", "-m", "Initial", "--author=Initial <auto@mated.null>", "--allow-empty").executeSilently()
        git("branch", remappedBranch).executeSilently()
        git("branch", unmappedBranch).executeSilently()
        git("checkout", unmappedBranch).executeSilently()
    }

    fun checkoutRemapped() {
        println("Switching to $remappedBranch without losing changes")
        git("symbolic-ref", "HEAD", "refs/heads/$remappedBranch").executeSilently()
    }

    fun checkoutOld() {
        println("Resetting back to $unmappedBranch branch")
        git("checkout", unmappedBranch).executeSilently()
    }

    fun commitInitialSource() {
        git("add", ".").executeSilently()
        git("commit", "-m", "Initial Source", "--author=Initial <auto@mated.null>").executeSilently()
    }

    fun recordCommit() {
        commitMessage = git("git", "log", "--format=%B", "-n", "1", "HEAD").getText()
        commitAuthor = git("git", "log", "--format=%an <%ae>", "-n", "1", "HEAD").getText()
        commitTime = git("git", "log", "--format=%aD", "-n", "1", "HEAD").getText()
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
        }
    }
}
