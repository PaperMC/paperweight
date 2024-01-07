package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class SetupCraftBukkit : BaseTask() {

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:InputDirectory
    abstract val decompiledSource: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val perFilePatchesToApply: DirectoryProperty

    @get:Input
    abstract val recreateAsPatches: ListProperty<String>

    @get:OutputDirectory
    abstract val finalPerFilePatchesDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(targetDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val patchesToApplyDir = perFilePatchesToApply.pathOrNull ?: targetDir.path.resolve("nms-patches")
        val patchList = Files.walk(patchesToApplyDir).use { it.asSequence().filter { file -> file.isRegularFile() }.toSet() }
        if (patchList.isEmpty()) {
            throw PaperweightException("No patch files found in $patchesToApplyDir")
        }
        val git = Git(targetDir.path)

        val basePatchDirFile = targetDir.path.resolve("src/main/java")
        // Copy in patch targets
        for (file in patchList) {
            val javaName = javaFileName(patchesToApplyDir, file)
            val out = basePatchDirFile.resolve(javaName)
            val sourcePath = decompiledSource.path.resolve(javaName)

            out.parent.createDirectories()
            sourcePath.copyTo(out)
        }

        for (file in patchList) {
            val javaName = javaFileName(patchesToApplyDir, file)
            println("Patching ${javaName.removeSuffix(".java")}")
            val dirPrefix = basePatchDirFile.relativeTo(outputDir.path).invariantSeparatorsPathString
            git("apply", "--ignore-whitespace", "--directory=$dirPrefix", file.absolutePathString()).execute()
        }

        val newPatchesDir = finalPerFilePatchesDirectory.path;
        newPatchesDir.deleteRecursive()
        project.copy {
            from(patchesToApplyDir)
            into(newPatchesDir)
        }
        targetDir.path.resolve("nms-patches").deleteRecursive()
        git("add", "nms-patches", newPatchesDir.absolutePathString()).execute()

        recreateAsPatches.get().forEach { toRecreate ->
            val patchedFile = targetDir.file(toRecreate).path
            val fileName = patchedFile.absolutePathString().split("src/main/java/", limit = 2)[1]
            val nmsFile = decompiledSource.get().file(fileName).asFile
            val patchFile = newPatchesDir.resolve(fileName).resolveSibling((patchedFile.nameWithoutExtension + ".patch"))

            val commandText =
                listOf<String>("diff", "-u", "--label", "a/$fileName", nmsFile.absolutePath, "--label", "b/$fileName", patchedFile.absolutePathString())
            val processBuilder = ProcessBuilder(commandText).directory(targetDir.get().asFile)
            val command = Command(processBuilder, commandText.joinToString(" "), arrayOf(0, 1))
            val output = command.getText()

            Files.createDirectories(patchFile.parent)
            patchFile.bufferedWriter().use {
                it.write(output)
            }
            git("rm", "--cached", toRecreate).execute()
            git("add", patchFile.absolutePathString()).execute()
        }

        git("commit", "-m", "Convert CraftBukkit patches to new layout", "--author=Initial <auto@mated.null>").execute()
    }

    private fun javaFileName(rootDir: Path, file: Path): String {
        return file.relativeTo(rootDir).toString().replaceAfterLast('.', "java")
    }
}
