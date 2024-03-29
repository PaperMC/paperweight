package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class RebuildPerFilePatches : BaseTask() {

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:InputDirectory
    abstract val spigotDecompiledSource: DirectoryProperty

    @get:Input
    abstract val directoriesToPatch: ListProperty<String>

    @get:Input
    abstract val commitMsg: Property<String>

    @get:OutputDirectory
    abstract val sourcePatchDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(targetDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val sourcePatchesDir = sourcePatchDir.path
        sourcePatchesDir.resolve("net/minecraft").deleteRecursive()

        rebuildPerFilePatches(targetDir.asFileTree.matching {
            directoriesToPatch.get().forEach { include("$it/**/*.java") }
        }, spigotDecompiledSource, sourcePatchesDir)

        Git(targetDir).let { git ->
            git("add", sourcePatchesDir.absolutePathString(), "src/test", "src/main/java/org").execute()
            git("commit", "-m", commitMsg.get(), "--author=Initial Source <auto@mated.null>").execute()
        }
    }

    companion object {

        fun rebuildPerFilePatches(source: Iterable<File>, originalSource: DirectoryProperty, patchDir: Path) {
            source.forEach { sourceFile ->
                createPerFilePatch(sourceFile.toPath(), originalSource, patchDir)
            }
        }

        private fun createPerFilePatch(patchedFile: Path, originalSource: DirectoryProperty, patchDir: Path) {
            val fileName = patchedFile.absolutePathString().split("src/main/java/", limit = 2)[1]
            val nmsFile = originalSource.file(fileName).path
            val patchFile = patchDir.resolve(fileName).resolveSibling((patchedFile.name + ".patch")) // keep extension

            val commandText = listOf("diff", "-u", "--label", "a/$fileName", nmsFile.absolutePathString(), "--label", "b/$fileName", patchedFile.absolutePathString())
            val processBuilder = ProcessBuilder(commandText)
            val command = Command(processBuilder, commandText.joinToString(" "), arrayOf(0, 1))
            val output = command.getText()

            patchFile.deleteRecursive()
            Files.createDirectories(patchFile.parent)
            patchFile.bufferedWriter().use {
                it.write(output)
            }
        }
    }
}
