package io.papermc.paperweight.tasks.mm.filterrepo

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.*

@UntrackedTask(because = "git")
abstract class RewriteCommits : BaseTask() {

    companion object {
        const val RESET_CALLBACK = """
            commit.committer_name  = commit.author_name
            commit.committer_email = commit.author_email
            commit.committer_date  = commit.author_date
            """

        @Suppress("MemberVisibilityCanBePrivate")
        const val COMMIT_MSG = "commit.message = commit.message + b'By: ' + commit.author_name + b' <' + commit.author_email + b'>'"

        const val CRAFTBUKKIT_CALLBACK = """
            $COMMIT_MSG
            commit.author_name     = b'CraftBukkit/Spigot'
            commit.author_email    = b'craftbukkit.spigot@github.com'
            $RESET_CALLBACK
            """

        const val BUKKIT_CALLBACK = """
            $COMMIT_MSG
            commit.author_name     = b'Bukkit/Spigot'
            commit.author_email    = b'bukkit.spigot@github.com'
            $RESET_CALLBACK
            """

        const val PAPER_CALLBACK = """
            msg    = commit.message.decode('utf-8')
            author = commit.author_email.decode('utf-8')
            if author == 'aikar@aikar.co' and (msg.startswith('[CI-SKIP] [Auto]') or msg.startswith('[Auto]')):
                commit.author_name   = b'Automated'
                commit.author_email  = b'auto@mated.null'
            $RESET_CALLBACK
        """
    }

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:Input
    abstract val paths: ListProperty<String>

    @get:Input
    abstract val pathGlobs: ListProperty<String>

    @get:Input
    abstract val commitCallback: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(targetDir)
        paths.convention(objects.listProperty())
        pathGlobs.convention(objects.listProperty())
        commitCallback.convention(RESET_CALLBACK)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val pathsArr = paths.get().map { "--path=$it" }.toTypedArray()
        val pathGlobsArr = pathGlobs.get().map { "--path-glob=$it" }.toTypedArray()
        Git(targetDir)("filter-repo", *pathsArr, *pathGlobsArr, "--force", "--commit-callback", commitCallback.get()).executeOut()
    }
}
