package io.papermc.paperweight.tasks

import io.papermc.paperweight.ext.listFilesRecursively
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fileOrNull
import java.io.File
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional

abstract class ApplyRawDiffPatches : ZippedTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val keepDir: Property<String>

    override fun init() {
        outputZip.convention(defaultOutput("zip"))
    }

    override fun run(rootDir: File) {
        val input = inputDir.file
        input.copyRecursively(rootDir)

        val patches = patchDir.fileOrNull ?: return
        val patchSet = patches.listFilesRecursively() ?: return

        val git = Git(rootDir)

        patchSet.asSequence()
            .filter { it.name.endsWith(".patch") }
            .sorted()
            .forEach { patch ->
                git("apply", patch.absolutePath).executeOut()
            }
    }
}
