package io.papermc.paperweight.tasks.mm.filterrepo

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class MoveCommits : BaseTask() {

    @get:InputDirectory
    abstract val repoDir: DirectoryProperty

    @get:Input
    abstract val toDir: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(repoDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        Git(repoDir)("filter-repo", "--to-subdirectory-filter", toDir.get(), "--force", "--commit-callback", RewriteCommits.RESET_CALLBACK).executeOut()
    }
}
