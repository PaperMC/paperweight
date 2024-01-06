package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyApiPatches : BaseTask() {

    @get:InputDirectory
    abstract val bukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val unneededFiles: ListProperty<String>

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
            if (unneededFiles.isPresent && unneededFiles.get().size > 0) {
                unneededFiles.get().forEach { path -> outputDir.path.resolve(path).deleteRecursive() }
                git(*Git.add(false, ".")).executeSilently()
                git("commit", "-m", "Initial", "--author=Initial Source <auto@mated.null>").executeSilently()
            }
            patchesDir.asFileTree.files.sorted().forEach { patch ->
                git("am", patch.absolutePath).execute()
            }
        }
    }
}
