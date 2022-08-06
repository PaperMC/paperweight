package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.util.*
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ProjectLayout
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Git")
abstract class ResetSubmodules : DefaultTask() {

    @get:Inject
    abstract val layout: ProjectLayout

    init {
        group = "mm"
    }

    @TaskAction
    fun run() {
        layout.resetSubmodules()
    }
}
