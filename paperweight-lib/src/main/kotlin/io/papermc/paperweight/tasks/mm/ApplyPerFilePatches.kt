package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyPerFilePatches : BaseTask() {

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:InputDirectory
    abstract val perFilePatches: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        super.init()
        group = "mm"
        outputDir.convention(targetDir)
    }

    @TaskAction
    fun run() {
        val patchesToApplyDir = perFilePatches.path
        val patchList = Files.walk(patchesToApplyDir).use { it.asSequence().filter { file -> file.isRegularFile() }.toSet() }
        val basePatchDirFile = targetDir.path.resolve("src/main/java")
        val git = Git(targetDir.path)
        for (file in patchList) {
            val javaName = javaFileName(patchesToApplyDir, file)
            println("Patching ${javaName.removeSuffix(".java")}")
            val dirPrefix = basePatchDirFile.relativeTo(outputDir.path).invariantSeparatorsPathString
            git("apply", "--ignore-whitespace", "--directory=$dirPrefix", file.absolutePathString()).execute()
        }
    }
}
