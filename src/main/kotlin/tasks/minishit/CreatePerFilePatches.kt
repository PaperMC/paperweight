package io.papermc.paperweight.tasks.minishit

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.Command
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.unzip
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException

abstract class CreatePerFilePatches : BaseTask() {

    @get:InputFile
    abstract val spigotSources: RegularFileProperty
    @get:InputFile
    abstract val minecraftSources: RegularFileProperty

    @TaskAction
    fun run() {
        // create folders
        val root = createWorkDir("minishit", recreate = true)
        val spigot = createWorkDir("minishit/spigot", recreate = false)
        val minecraft = createWorkDir("minishit/minecraft", recreate = false)
        val patches = createWorkDir("minishit/patches", recreate = false)

        // unzip
        unzip(spigotSources.get(), spigot)
        unzip(minecraftSources.get(), minecraft)

        // run diff
        spigot.walkTopDown().filter { it.isFile }.forEach {
            val relativeFile = it.relativeTo(spigot)
            val minecraftFile = minecraft.resolve(relativeFile)
            println("Diffing $relativeFile")
            val out = execute("diff", "-u", "-N", "--label", "a/$relativeFile", "$minecraftFile", "--label", "b/$relativeFile", "$it", dir = root).getText()
            val outFile = patches.resolve(relativeFile.toString().replace(".java", ".patch"))
            outFile.parentFile.mkdirs()
            outFile.writeText(out)
        }

        // zip patches
    }

    private fun createWorkDir(name: String, source: File? = null, recreate: Boolean = true): File {
        return layout.cache.resolve("paperweight").resolve(name).apply {
            if (recreate) {
                deleteRecursively()
                mkdirs()
                source?.copyRecursively(this)
            }
        }
    }

    private fun execute(vararg cmd: String, dir: File) : Command {
        return try {
            Command(ProcessBuilder(*cmd).directory(dir).start(), cmd.joinToString(separator = " "), ignoreError = true)
        } catch (e: IOException) {
            throw PaperweightException("Failed to execute command: ${cmd.joinToString(separator = " ")}", e)
        }
    }
}
