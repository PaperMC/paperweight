package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mm.filterrepo.RewriteCommits
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
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
            regex:index\s([a-fA-F0-9]{7})\.\.([a-fA-F0-9]{7})==>index 0000000000000000000000000000000000000000..0000000000000000000000000000000000000000
            regex:From\s([a-fA-F0-9]{40})==>From 0000000000000000000000000000000000000000
            regex:--\s*\d+\.\d+\.\d+(\.windows\.\d+)?\s*${'$'}==>--
        """

        const val UPDATE_AUTHOR = """
            replacements = {
                %s
            }
            for original, replacement in replacements.items():
                commit.message = commit.message.replace(original, replacement[0] + b' <' + replacement[1] + b'>')
            fullDetails = commit.author_name + b' <' + commit.author_email + b'>'
            if fullDetails in replacements:
                replacement = replacements[fullDetails]
                commit.author_name = replacement[0]
                commit.author_email = replacement[1]
                commit.committer_name  = commit.author_name
                commit.committer_email = commit.author_email
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
                commit.author_email = b'noreply+git-spigot@papermc.io'
            ${RewriteCommits.RESET_CALLBACK}""".trimIndent()
            git("filter-repo", "--force", "--commit-callback", callbackArg).executeOut()

            firstCommit = git("log", "--grep=Rename to PaperSpigot", "--format=%H").getText().trim()
            println("Removing index and line number changes from patch diff and renaming patch files")
            val tempFile = Files.createTempFile("paperweight", "filter-repo")

            // Update author details in patch files and commit details
            var replaceText = "";
            var dictCode = "";
            AuthorDetails.readAuthorChanges().forEach { authorDetails ->
                authorDetails.oldDetails.forEach{ oldDetail ->
                    replaceText += "\n" + "${oldDetail}==>${authorDetails.newName} <${authorDetails.newEmail}>"
                    dictCode += "\n" + "b'${oldDetail}': (b'${authorDetails.newName}', b'${authorDetails.newEmail}'),"
                }
            }

            Files.writeString(tempFile, REPLACE_TEXT.trimIndent() + replaceText)
            git(
                "filter-repo",
                "--replace-text", tempFile.toAbsolutePath().toString(),
                "--path", "patches/removed/",
                "--path", "removed/",
                "--path", "patches/server-unmapped/",
                "--path", "Remapped-Spigot-Server-Patches/",
                "--path", "Unmapped-Spigot-Server-Patches/",
                "--invert-paths",
                "--commit-callback", UPDATE_AUTHOR.format(dictCode).trimIndent(),
                "--filename-callback", """
                    import re
                    import string
                    import random

                    # filter-repo bug where it will pass None for removed files...
                    if filename is None:
                        return b""

                    pattern = re.compile(br"^((?:patches|Spigot-(?:API|Server)-Patches)(?:/.*?)?)/\d{4}-(.*)")
                    match = pattern.match(filename)
                    if match:
                        folder = match.group(1)
                        patch_name = match.group(2)

                        # Keep numbered prefix to avoid conflict in these two...
                        if patch_name == b"fixup-MC-Utils.patch":
                            return filename
                        if patch_name == b"Brand-support.patch" and folder.startswith(b"Spigot"):
                            return filename

                        # Dir, subdirs if present, then the filename without the numbered prefix
                        return folder + b"/" + patch_name
                    else:
                        return filename
                    """.trimIndent(),
                "--refs", "$firstCommit..HEAD"
            ).executeOut()
            Files.deleteIfExists(tempFile)
        }
    }
}