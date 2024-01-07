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
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val decompiledSource: DirectoryProperty

    @get:Input
    abstract val recreateAsPatches: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(craftBukkitDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()
        val nmsPatches = craftBukkitDir.path.resolve("nms-patches")
        if (recreateAsPatches.get().isNotEmpty()) {
            craftBukkitDir.asFileTree.matching {
                include(recreateAsPatches.get())
            }.forEach { patchedFile ->
                val fileName = patchedFile.absolutePath.split("src/main/java/", limit = 2)[1]
                val nmsFile = decompiledSource.get().file(fileName).asFile
                val patchFile = nmsPatches.resolve(fileName).resolveSibling((patchedFile.nameWithoutExtension + ".patch"))

                val commandText =
                    listOf<String>("diff", "-u", "--label", "a/$fileName", nmsFile.absolutePath, "--label", "b/$fileName", patchedFile.absolutePath)
                val processBuilder = ProcessBuilder(commandText).directory(craftBukkitDir.get().asFile)
                val command = Command(processBuilder, commandText.joinToString(" "), arrayOf(0, 1))
                val output = command.getText()

                Files.createDirectories(patchFile.parent)
                patchFile.bufferedWriter().use {
                    it.write(output)
                }
            }

            Git(craftBukkitDir).let { git ->
                recreateAsPatches.get().forEach {
                    git("rm", "--cached", it).execute()
                    git("add", "nms-patches/${it.removeSurrounding("src/main/java/", ".java")}.patch").execute()
                }
                git("commit", "-m", "Convert CraftBukkit source to patches", "--author=Initial <auto@mated.null>").execute()
            }
        }

    }
}
