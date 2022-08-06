package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mm.filterrepo.RewriteCommits
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "git")
abstract class RewritePartialPaperHistory : BaseTask() {

    @get:InputDirectory
    abstract val paperDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(paperDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        Git(paperDir).let { git ->
            val firstCommit = git("log", "--grep=Rename to PaperSpigot", "--format=%H").getText().trim()
            val lastRenameCommit = git("log", "-1", "--format=%H", "$firstCommit~1").getText().trim()
            val firstRenameCommit = git("rev-list", "--max-parents=0", "HEAD").getText().trim()

            val commitsToRewrite = git("rev-list", "--ancestry-path", "$firstRenameCommit..$lastRenameCommit").getText()
                .trim().lines().map {
                    "commit.original_id == b'${it}'"
                }
            println("Commits to rewrite: ${commitsToRewrite.size}")

            git("filter-repo", "--force", "--commit-callback", """
                if ${commitsToRewrite.joinToString(" or ")}:
                    ${RewriteCommits.COMMIT_MSG}
                    commit.author_name = b'Spigot'
                    commit.author_email = b'spigot@github.com'
                ${RewriteCommits.RESET_CALLBACK.lines().joinToString("\n") { "    $it" }}""".trimIndent()).executeOut()
        }
    }
}
