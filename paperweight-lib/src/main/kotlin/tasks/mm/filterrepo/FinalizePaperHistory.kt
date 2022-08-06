package io.papermc.paperweight.tasks.mm.filterrepo

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.*

@UntrackedTask(because = "git")
abstract class FinalizePaperHistory : BaseTask() {

    @get:InputDirectory
    abstract val paperDir: DirectoryProperty

    @get:Input
    abstract val deletions: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(paperDir)
        deletions.convention(objects.listProperty())
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val deletionsArr = deletions.get().toTypedArray()
        if (deletionsArr.isNotEmpty()) {
            Git(paperDir).let { git ->
                git("rm", "-r", *deletionsArr).execute()
                git("commit", "-m", "OWW! That fork is HARD!", "--author=Automated <auto@mated.null>").execute()
            }
        }
    }
}
