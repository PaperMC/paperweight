package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class CollectATsFromPatches : BaseTask() {

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        outputFile.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        outputFile.path.deleteForcefully()
        val patches = patchDir.path.listDirectoryEntries("*.patch")
        outputFile.path.writeLines(readAts(patches))
    }

    private fun readAts(patches: Iterable<Path>) : Set<String> {
        val result = hashSetOf<String>()

        val start = "== AT =="
        val end = "diff --git a/"
        for (patch in patches) {
            var reading = false
            for (readLine in patch.readLines()) {
                if (readLine.startsWith(end)) {
                    break
                }
                if (reading && readLine.isNotBlank()) {
                    result.add(readLine)
                }
                if (readLine.startsWith(start)) {
                    reading = true
                }
            }
        }
        return result
    }
}
