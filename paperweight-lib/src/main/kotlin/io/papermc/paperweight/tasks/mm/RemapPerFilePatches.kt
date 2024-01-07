package io.papermc.paperweight.tasks.mm

import com.github.salomonbrys.kotson.fromJson
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class RemapPerFilePatches : BaseTask() {

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputFile
    abstract val remapPatch: RegularFileProperty

    @get:InputFile
    abstract val caseOnlyClassNameChanges: RegularFileProperty

    @get:InputDirectory
    abstract val decompiledSourceFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(craftBukkitDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()
        val caseOnlyChanges = caseOnlyClassNameChanges.path.bufferedReader(Charsets.UTF_8).use { reader ->
            gson.fromJson<List<ClassNameChange>>(reader)
        }

        Git(craftBukkitDir).let { git ->
            val sourceDir = craftBukkitDir.path.resolve("src/main/java")
            val excludes = arrayListOf<String>()
            for (caseOnlyChange in caseOnlyChanges) {
                val file = sourceDir.resolve(caseOnlyChange.obfName + ".java")
                file.deleteForcefully()
                excludes += "--exclude=src/main/java/${caseOnlyChange.obfName}.java"
            }
            git("apply", *excludes.toTypedArray(), "--exclude=nms-patches/**", remapPatch.path.absolutePathString()).execute()
        }

        val nmsPatches = craftBukkitDir.path.resolve("nms-patches")
        nmsPatches.resolve("net/minecraft").deleteRecursive()

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
            git("add", nmsPatches.absolutePathString(), "src/test", "src/main/java/org").execute()
            git("commit", "-m", "CraftBukkit/Spigot Patch Remap", "--author=Initial Source <auto@mated.null>").execute()
        }
    }
}
