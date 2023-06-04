package io.papermc.paperweight.tasks.mm

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
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Git")
abstract class CloneRepos : BaseTask() {

    @get:Input
    abstract val cloneBranch: Property<String>

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val bukkitDir: DirectoryProperty

    @get:OutputDirectory
    abstract val craftBukkitClone: DirectoryProperty

    @get:OutputDirectory
    abstract val bukkitClone: DirectoryProperty

    @get:OutputDirectory
    abstract val paperClone: DirectoryProperty

    override fun init() {
        group = "mm"
        craftBukkitClone.convention(project, craftBukkitDir.map {
            it.path.resolveSibling("CraftBukkitClone")
        })
        bukkitClone.convention(project, bukkitDir.map {
            it.path.resolveSibling("BukkitClone")
        })
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        listOf(
            Pair(craftBukkitClone, craftBukkitDir),
            Pair(bukkitClone, bukkitDir),
            Pair(paperClone, project.rootProject.rootDir)
        ).forEach {
            it.first.path.deleteRecursive()
            Git(it.second).let { git ->
                git("branch", "-f", cloneBranch.get(), "HEAD").runSilently(silenceErr = true)
                git("clone", ".", it.first.path.absolutePathString(), "--branch", cloneBranch.get()).execute()
            }
        }
    }
}
