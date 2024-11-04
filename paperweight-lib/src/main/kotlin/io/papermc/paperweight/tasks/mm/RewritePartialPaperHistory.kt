package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mm.filterrepo.RewriteCommits
import io.papermc.paperweight.util.*
import java.nio.file.Files
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "git")
abstract class RewritePartialPaperHistory : BaseTask() {

    companion object {
        const val REPLACE_TEXT = """
            regex:@@\s-\d+,\d+\s\+\d+,\d+\s@@==>@@ -0,0 +0,0 @@
            regex:index\s([a-fA-F0-9]{40})\.\.([a-fA-F0-9]{40})==>index 0000000000000000000000000000000000000000..0000000000000000000000000000000000000000
        """
    }

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
            var firstCommit = git("log", "--grep=Rename to PaperSpigot", "--format=%H").getText().trim()
            val lastRenameCommit = git("log", "-1", "--format=%H", "$firstCommit~1").getText().trim()
            val firstRenameCommit = git("rev-list", "--max-parents=0", "HEAD").getText().trim()

            val commitsToRewrite = git("rev-list", "--ancestry-path", "$firstRenameCommit..$lastRenameCommit").getText()
                .trim().lines().map {
                    "commit.original_id == b'${it}'"
                }
            println("Commits to rewrite: ${commitsToRewrite.size}")

            val callbackArg = """
            ${RewriteCommits.UTILS}
            if ${commitsToRewrite.joinToString(" or ")}:
                ${RewriteCommits.COMMIT_MSG.lines().joinToString("\n") { "    $it" }}
                commit.author_name = b'Spigot'
                commit.author_email = b'spigot@github.com'
            ${RewriteCommits.RESET_CALLBACK}""".trimIndent()
            git("filter-repo", "--force", "--commit-callback", callbackArg).executeOut()

            firstCommit = git("log", "--grep=Rename to PaperSpigot", "--format=%H").getText().trim()
            println("Removing index and line number changes from patch diff and renaming patch files")
            val tempFile = Files.createTempFile("paperweight", "filter-repo")
            Files.writeString(tempFile, REPLACE_TEXT.trimIndent())
            git(
                "filter-repo",
                "--replace-text", tempFile.toAbsolutePath().toString(),
                "--path", "patches/removed/",
                "--path", "removed/",
                "--invert-paths",
                "--filename-callback", """
                    import re
                    import string
                    import random

                    # filter-repo bug where it will pass None for removed files...
                    if filename is None:
                        return b""

                    pattern = re.compile(br"^((?:patches|Spigot-(?:API|Server)-Patches)/.*?)/\d{4}-(.*)")
                    match = pattern.match(filename)
                    if match:
                        # Avoid remaining conflicts manually
                        if match.group(2) in {b"fixup-MC-Utils.patch"}:
                            prefix = b''.join(random.choice(string.ascii_lowercase).encode('utf-8') for _ in range(4))
                            return match.group(1) + b"/" + prefix + b"-" + match.group(2)

                        # Dir, subdirs if present, then the filename without the numbered prefix
                        return match.group(1) + b"/" + match.group(2)
                    else:
                        return filename
                    """.trimIndent(),
                "--refs", "$firstCommit..HEAD"
            ).executeOut()
            Files.deleteIfExists(tempFile)
        }
    }
}