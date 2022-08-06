package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyApiPatches : BaseTask() {

    @get:InputDirectory
    abstract val bukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(bukkitDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        Git(bukkitDir).let { git ->
            patchesDir.asFileTree.files.sorted().forEach { patch ->
                git("am", patch.absolutePath).execute()
            }
        }
    }
}
