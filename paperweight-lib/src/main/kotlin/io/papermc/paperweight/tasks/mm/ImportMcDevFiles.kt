package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
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

abstract class ImportMcDevFiles : BaseTask() {

    @get:InputDirectory
    @get:Optional
    abstract val patchDir: DirectoryProperty

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val devImports: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val filesToImport: ListProperty<String>

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val spigotLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val targetDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val libraryOutputDir: DirectoryProperty

    override fun init() {
        group = "mm"
        outputDir.convention(targetDir)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val patches = patchDir.map { it.path.listDirectoryEntries("*.patch") }.orElse(emptyList()).get()
        McDev.importMcDev(
            patches = patches,
            decompJar = sourceMcDevJar.path,
            importsFile = importsFile(),
            targetDir = targetDir.path.resolve("src/main/java"),
            dataTargetDir = targetDir.path.resolve("src/main/resources"),
            librariesDirs = listOf(spigotLibrariesDir.path, mcLibrariesDir.path),
            secondaryLibraryTargetDir = libraryOutputDir.path
        )
    }

    private fun importsFile(): Path? {
        if (devImports.isPresent) {
            return devImports.path
        } else if (filesToImport.isPresent) {
            val temp = createTempFile("importMcDevFile", "txt")
            temp.writeLines(filesToImport.get())
            return temp
        }
        return null
    }
}
