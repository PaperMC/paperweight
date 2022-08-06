package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class RemapCraftBukkitPatches : BaseTask() {

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputFile
    abstract val decompiledJar: RegularFileProperty

    @get:OutputDirectory
    abstract val decompiledSourceFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val remappedPatches: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        decompiledSourceFolder.convention(objects.directoryProperty().convention(layout.cacheDir(DECOMPILED_SOURCE_FOLDER)))
        remappedPatches.convention(craftBukkitDir.dir("nms-patches"))
        outputDir.convention(craftBukkitDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        fs.copy {
            from(archives.zipTree(decompiledJar))
            into(decompiledSourceFolder)
        }

        val nmsPatches = remappedPatches.path

        craftBukkitDir.asFileTree.matching {
            include("src/main/java/net/minecraft/**/*.java")
        }.forEach { patchedFile ->
            val fileName = patchedFile.absolutePath.split("src/main/java/", limit = 2)[1]
            val nmsFile = decompiledSourceFolder.get().file(fileName).asFile
            val patchFile = nmsPatches.resolve(fileName).resolveSibling((patchedFile.nameWithoutExtension + ".patch"))

            val commandText = listOf<String>("diff", "-u", "--label", "a/$fileName", nmsFile.absolutePath, "--label", "b/$fileName", patchedFile.absolutePath)
            val processBuilder = ProcessBuilder(commandText).directory(craftBukkitDir.get().asFile)
            val command = Command(processBuilder, commandText.joinToString(" "), arrayOf(0, 1))
            val output = command.getText()

            Files.createDirectories(patchFile.parent)
            patchFile.bufferedWriter().use {
                it.write(output)
            }
        }

        Git(craftBukkitDir).let { git ->
            git("reset", "--hard", "HEAD~1").execute()
            git("add", "nms-patches/net").execute()
            git("commit", "-m", "CB & Spigot NMS Patch Remap", "--author=Initial Source <auto@mated.null>").execute()
        }
    }
}
