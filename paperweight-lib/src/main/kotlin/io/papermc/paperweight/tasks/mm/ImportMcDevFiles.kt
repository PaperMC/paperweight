package io.papermc.paperweight.tasks.mm

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.asSequence
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

    @get:InputFile
    abstract val sourceMcDevJar: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val sourceMcDevJarSrc: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val perCommitPatchDir: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val perFilePatchDir: DirectoryProperty

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

        val patches = perCommitPatchDir.map { it.path.listDirectoryEntries("*.patch") }.orElse(emptyList()).get()
        McDev.importMcDev(
            patches = patches,
            decompJar = sourceMcDevJar.path,
            importsFile = importsFile(),
            targetDir = targetDir.path.resolve("src/main/java"),
            dataTargetDir = targetDir.path.resolve("src/main/resources"),
            librariesDirs = listOf(spigotLibrariesDir.path, mcLibrariesDir.path),
            secondaryLibraryTargetDir = libraryOutputDir.path
        )

        if (perFilePatchDir.isPresent && sourceMcDevJarSrc.isPresent) {
            val perFilePatches = perFilePatchDir.path
            val patchList = Files.walk(perFilePatches).use { it.asSequence().filter { file -> file.isRegularFile() }.toSet() }
            if (patchList.isNotEmpty()) {
                val basePatchDirFile = targetDir.path.resolve("src/main/java")
                // Copy in patch targets
                for (file in patchList) {
                    val javaName = javaFileName(perFilePatches, file)
                    val out = basePatchDirFile.resolve(javaName)
                    val sourcePath = sourceMcDevJarSrc.path.resolve(javaName)

                    out.parent.createDirectories()
                    sourcePath.copyTo(out, true)
                }
            }

        }
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
