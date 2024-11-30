package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class SetupCraftBukkit : BaseTask() {

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:InputDirectory
    abstract val perFilePatches: DirectoryProperty

    @get:InputDirectory
    abstract val sourceForPatchRecreation: DirectoryProperty

    @get:Input
    abstract val recreateAsPatches: ListProperty<String>

    @get:OutputDirectory
    abstract val newPerFilePatchesDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(targetDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        // move to new patch location
        val patchesToApplyDir = perFilePatches.path
        val git = Git(targetDir.path)
        val newPatchesDir = newPerFilePatchesDirectory.path;
        newPatchesDir.deleteRecursive()
        project.copy {
            from(patchesToApplyDir)
            into(newPatchesDir)
        }
        targetDir.path.resolve("nms-patches").deleteRecursive() // delete old directory
        git("add", "nms-patches", newPatchesDir.absolutePathString()).execute()

        // converts files that CB had previously included in their entirety from external libraries
        // to patches of the original library source files
        recreateAsPatches.get().forEach { toRecreate ->
            val patchedFile = targetDir.file(toRecreate).path
            val fileName = patchedFile.absolutePathString().split("src/main/java/", limit = 2)[1]
            val nmsFile = sourceForPatchRecreation.get().file(fileName).asFile
            val patchFile = newPatchesDir.resolve(fileName).resolveSibling((patchedFile.name + ".patch")) // keep extension

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

        git("commit", "-m", "Move CraftBukkit per-file patches", "--author=Initial <noreply+automated@papermc.io>").execute()
    }
}
