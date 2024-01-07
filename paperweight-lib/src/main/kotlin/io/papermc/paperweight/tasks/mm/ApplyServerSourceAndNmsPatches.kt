package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class ApplyServerSourceAndNmsPatches : BaseTask() {

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:InputDirectory
    abstract val sourcePatches: DirectoryProperty

    @get:InputFile
    @get:Optional
    abstract val initialCraftBukkitSpigotPatch: RegularFileProperty

    @get:Input
    abstract val directoriesToPatch: ListProperty<String>

    @get:InputDirectory
    abstract val decompiledSource: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val unneededFiles: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(craftBukkitDir)
        directoriesToPatch.convention(objects.listProperty(String::class))
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        Git(craftBukkitDir).let { git ->
            if (initialCraftBukkitSpigotPatch.isPresent) {
                git("apply", initialCraftBukkitSpigotPatch.path.absolutePathString()).execute()
            }

            val nmsPatches = craftBukkitDir.path.resolve("nms-patches")
            sourcePatches.asFileTree.files.sorted().forEach { patch ->
                val patchName = patch.name.split("-", limit = 2)[1]
                println("Applying Patch: $patchName")
                val excludeArray = directoriesToPatch.get().map { "--exclude=$it/**" }.toTypedArray()
                git("am", "--ignore-whitespace", *excludeArray, patch.toPath().absolutePathString()).execute()
                val includeArray = directoriesToPatch.get().map { "--include=$it/**" }.toTypedArray()
                git("apply", "--ignore-whitespace", *includeArray, patch.toPath().absolutePathString()).execute()

                val javaTypes = McDev.readPatchLines(listOf(patch.toPath())).first.map { "src/main/java/$it" }.filter {
                    directoriesToPatch.get().any { dir -> it.startsWith(dir) }
                }
                if (javaTypes.isNotEmpty()) {
                    javaTypes.forEach { type ->
                        val patchedFile = craftBukkitDir.file(type).get().asFile
                        val fileName = patchedFile.absolutePath.split("src/main/java/", limit = 2)[1]
                        val nmsFile = decompiledSource.file(fileName).get().asFile
                        val patchFile = nmsPatches.resolve(fileName).resolveSibling((patchedFile.nameWithoutExtension + ".patch"))

                        val commandText = listOf<String>(
                            "diff",
                            "-u",
                            "--label",
                            "a/$fileName",
                            nmsFile.absolutePath,
                            "--label",
                            "b/$fileName",
                            patchedFile.absolutePath
                        )
                        val processBuilder = ProcessBuilder(commandText).directory(craftBukkitDir.path)
                        val command = Command(processBuilder, commandText.joinToString(" "), arrayOf(0, 1))
                        val output = command.getText()

                        Files.createDirectories(patchFile.parent)
                        patchFile.bufferedWriter().use {
                            it.write(output)
                        }
                    }
                    git("add", "nms-patches").execute()
                    git("commit", "--amend", "--no-edit").execute()
                }
            }

            if (unneededFiles.isPresent && unneededFiles.get().size > 0) {
                unneededFiles.get().forEach { path ->
                    outputDir.path.resolve(path).deleteRecursive()
                    git(*Git.add(false, path)).executeSilently()
                }
                git("commit", "-m", "Removed unneeded files", "--author=Initial Source <auto@mated.null>").executeSilently()
            }
        }

    }
}
