package io.papermc.paperweight.tasks.mm.filterrepo

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class MergeGitRepos : BaseTask() {

    @get:Input
    abstract val cloneBranch: Property<String>

    @get:InputDirectory
    abstract val bukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val paperDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val paperGit = Git(paperDir)
        val currentBranch = paperGit("rev-parse", "--abbrev-ref", "HEAD").getText().trim()

        outputDir.path.deleteRecursive()
        Files.createDirectories(outputDir.path)

        Git(outputDir).let { git ->
            git("init").execute()
            git("remote", "add", "bukkit", bukkitDir.path.absolutePathString()).execute()
            git("remote", "add", "craftbukkit", craftBukkitDir.path.absolutePathString()).execute()
            git("remote", "add", "paper", paperDir.path.absolutePathString()).execute()

            git("fetch", "--all").execute()

            git("merge", "bukkit/${cloneBranch.get()}", "--no-edit").execute()
            git("merge", "craftbukkit/${cloneBranch.get()}", "--no-edit", "--allow-unrelated-histories").execute()
            git("commit", "--amend", "--no-edit", "--author=Initial Source <auto@mated.null>").execute()
            git("merge", "paper/$currentBranch", "--no-edit", "--allow-unrelated-histories").execute()
            git("commit", "--amend", "--no-edit", "--author=Initial Source <auto@mated.null>").execute()
        }

//        bukkitDir.path.deleteRecursively()
//        craftBukkitDir.path.deleteRecursively()
//        paperDir.path.deleteRecursively()
    }
}
