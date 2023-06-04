package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class SetupPaper : BaseTask() {

    @get:Internal
    abstract val paperServer: Property<Project>

    @get:OutputDirectory
    abstract val paperNmsPatches: DirectoryProperty

    override fun init() {
        group = "mm"
        paperNmsPatches.convention(objects.directoryProperty().convention(layout.cacheDir(PAPER_NMS_PATCHES)))
    }

    @TaskAction
    fun run() {
        Git.checkForGit()
        paperNmsPatches.path.deleteRecursive()

        Git(paperServer.get().projectDir)("format-patch", "-o", paperNmsPatches.path.absolutePathString(), "--no-stat", "-N", "--zero-commit", "--full-index", "--no-signature", "base", "--",
            "src/main/java/net/minecraft", "src/main/java/com/mojang").execute() // TODO does all of com.mojang have to stay as patches?
    }
}
