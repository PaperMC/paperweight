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
        const val UTILS = """
            def insert_into_commit_msg(text):
                msg_split = commit.message.decode("utf-8").split("\n")
                idx = min([i for i,x in enumerate(msg_split) if x.find("Co-authored-by:") != -1 or x.find("By:") != -1 or x.find("Also-by:") != -1], default=None)
                if idx is not None:
                    msg_split.insert(idx, text.decode("utf-8"))
                    commit.message = "\n".join(msg_split).encode("utf-8")
                else:
                    commit.message = commit.message + b'\n' + text

            def replace_in_commit_msg(match, replace):
                commit.message = commit.message.decode("utf-8").replace(match, replace).encode("utf-8")
        """
        const val RESET_CALLBACK = """
            commit.committer_name  = commit.author_name
            commit.committer_email = commit.author_email
            commit.committer_date  = commit.author_date
            """

        @Suppress("MemberVisibilityCanBePrivate")
        const val COMMIT_MSG = """
            insert_into_commit_msg(b'By: ' + commit.author_name + b' <' + commit.author_email + b'>')
            replace_in_commit_msg("Co-authored-by:", "Also-by:")
        """

        const val CRAFTBUKKIT_CALLBACK = """
            $UTILS
            $COMMIT_MSG
            commit.author_name     = b'CraftBukkit/Spigot'
            commit.author_email    = b'craftbukkit.spigot@github.com'
            $RESET_CALLBACK
            """

        const val BUKKIT_CALLBACK = """
            $UTILS
            $COMMIT_MSG
            commit.author_name     = b'Bukkit/Spigot'
            commit.author_email    = b'bukkit.spigot@github.com'
            $RESET_CALLBACK
            """

        private const val MOVE_BACK_CALLBACK = """
            $UTILS
            import time
            date = commit.author_date.decode('utf-8').split(' ')
            commit.author_date = f'{int(date[0]) - 500000000} {date[1]}'.encode('utf-8')
            insert_into_commit_msg(b'Original-Date: ' + time.ctime(int(date[0])).encode("utf-8"))
        """

        const val PAPER_CALLBACK = """
            msg    = commit.message.decode('utf-8')
            author = commit.author_email.decode('utf-8')
            if author == 'aikar@aikar.co' and (msg.startswith('[CI-SKIP] [Auto]') or msg.startswith('[Auto]')):
                commit.author_name   = b'Automated'
                commit.author_email  = b'auto@mated.null'
            $MOVE_BACK_CALLBACK
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
