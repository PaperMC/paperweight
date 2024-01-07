package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class ApplyServerSourceAndNmsPatches : BaseTask() {

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:InputDirectory
    abstract val patchesToApply: DirectoryProperty

    @get:Input
    abstract val directoriesToPatch: ListProperty<String>

    @get:InputDirectory
    abstract val decompiledSource: DirectoryProperty

    @get:OutputDirectory
    abstract val sourcePatchDir: DirectoryProperty

    @get:OutputDirectory
    abstract val dataPatchDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val unneededFiles: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(targetDir)
        directoriesToPatch.convention(objects.listProperty(String::class))
    }

    private fun createPerFileDiff(patchDir: Path, files: Iterable<String>, prefix: String, splitPrefix: String = prefix) {
        files.map { "$prefix$it" }.filter { directoriesToPatch.get().any { dir -> it.startsWith(dir) } } .forEach { file ->
            val patchedFile = targetDir.file(file).get().asFile
            val fileName = patchedFile.absolutePath.split(splitPrefix, limit = 2)[1]
            val nmsFile = decompiledSource.file(fileName).get().asFile
            val patchFile = patchDir.resolve(fileName).resolveSibling((patchedFile.nameWithoutExtension + ".patch"))

            val commandText =
                listOf<String>("diff", "-u", "--label", "a/$fileName", nmsFile.absolutePath, "--label", "b/$fileName", patchedFile.absolutePath)
            val processBuilder = ProcessBuilder(commandText).directory(targetDir.path)
            val command = Command(processBuilder, commandText.joinToString(" "), arrayOf(0, 1))
            val output = command.getText()

            Files.createDirectories(patchFile.parent)
            patchFile.bufferedWriter().use {
                it.write(output)
            }
        }
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        Git(targetDir).let { git ->
            val sourcePatchesDir = sourcePatchDir.path
            val dataPatchesDir = dataPatchDir.path
            patchesToApply.asFileTree.files.sorted().forEach { patch ->
                val patchName = patch.name.split("-", limit = 2)[1]
                println("Applying Patch: $patchName")
                val excludeArray = directoriesToPatch.get().map { "--exclude=$it/**" }.toTypedArray()
                git("am", "--ignore-whitespace", *excludeArray, patch.toPath().absolutePathString()).execute()
                val includeArray = directoriesToPatch.get().map { "--include=$it/**" }.toTypedArray()
                git("apply", "--ignore-whitespace", *includeArray, patch.toPath().absolutePathString()).execute()

                val (sourceFiles, dataFiles) = McDev.readPatchLines(listOf(patch.toPath()))
                createPerFileDiff(sourcePatchesDir, sourceFiles, "src/main/java/")
                createPerFileDiff(dataPatchesDir, dataFiles, "src/main/resources/data/minecraft/", "src/main/resources/")
                if (sourceFiles.isNotEmpty() || dataFiles.isNotEmpty()) {
                    git("add", sourcePatchesDir.absolutePathString(), dataPatchesDir.absolutePathString()).execute()
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
