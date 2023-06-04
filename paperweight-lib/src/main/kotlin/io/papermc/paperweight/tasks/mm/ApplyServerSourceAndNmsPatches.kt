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

    @get:Input
    abstract val excludes: ListProperty<String>

    @get:InputFile
    @get:Optional
    abstract val initialVanillaSpigotPatch: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val initialCraftBukkitSpigotPatch: RegularFileProperty

    @get:InputDirectory
    abstract val nmsPatches: DirectoryProperty

    @get:OutputDirectory
    abstract val outputNmsPatches: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(craftBukkitDir)
        excludes.convention(objects.listProperty())
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        Git(craftBukkitDir).let { git ->
            if (initialVanillaSpigotPatch.isPresent) {
                git("am", initialVanillaSpigotPatch.path.absolutePathString()).execute()
            }
            if (initialCraftBukkitSpigotPatch.isPresent) {
                git("am", initialCraftBukkitSpigotPatch.path.absolutePathString()).execute()
            }

            val newNmsParts = outputNmsPatches.path
            Files.createDirectories(newNmsParts)
            sourcePatches.asFileTree.files.sorted().forEach { patch ->
                val patchName = patch.name.split("-", limit = 2)[1]
                val excludeArr = excludes.get().map { "--exclude=$it" }.toTypedArray()
                git("am", "--ignore-whitespace", *excludeArr, patch.toPath().absolutePathString()).execute()

                val matches = nmsPatches.asFileTree.matching {
                    include {
                        it.name.split("-", limit = 2)[1] == patchName
                    }
                }.toList()

                if (matches.size == 1) {
                    val filePath = matches[0].toPath()
                    val newFilePath = newNmsParts.resolve(filePath.fileName)
                    Files.copy(filePath, newFilePath)
                    git("add", newFilePath.absolutePathString()).execute()
                    git("commit", "--amend", "--no-edit").execute()
                } else if (matches.isNotEmpty()) {
                    throw Exception(matches.toString())
                }
            }
        }

    }
}
