package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ImportMcDevFiles : BaseTask() {

    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val spigotLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val libraryOutputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(craftBukkitDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val patches = patchDir.path.listDirectoryEntries("*.patch")
        McDev.importMcDev(
            patches = patches,
            decompJar = sourceMcDevJar.path,
            importsFile = devImports.pathOrNull,
            targetDir = craftBukkitDir.path.resolve("src/main/java"),
            dataTargetDir = craftBukkitDir.path.resolve("src/main/resources"),
            librariesDirs = listOf(spigotLibrariesDir.path, mcLibrariesDir.path),
            secondaryLibraryTargetDir = libraryOutputDir.path
        )
    }
}
