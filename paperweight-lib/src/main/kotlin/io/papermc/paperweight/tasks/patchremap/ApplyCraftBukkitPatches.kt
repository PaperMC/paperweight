package io.papermc.paperweight.tasks.patchremap

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.ControllableOutputTask
import io.papermc.paperweight.util.*
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ApplyCraftBukkitPatches : ControllableOutputTask() {

    @get:InputFile
    abstract val sourceJar: RegularFileProperty

    @get:Input
    abstract val cleanDirPath: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val patchZip: RegularFileProperty

    @get:Input
    abstract val ignoreGitIgnore: Property<Boolean>

    @get:InputDirectory
    abstract val craftBukkitDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val providers: ProviderFactory

    override fun init() {
        printOutput.convention(false)
        ignoreGitIgnore.convention(Git.ignoreProperty(providers)).finalizeValueOnRead()
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        outputDir.path.deleteRecursively()
        outputDir.path.parent.let {
            it.createDirectories()
            val git = Git(it)
            git("clone", "--no-hardlinks", craftBukkitDir.path.absolutePathString(), outputDir.path.absolutePathString()).setupOut().execute()
        }

        val git = Git(outputDir.path)

        val basePatchDirFile = outputDir.path.resolve("src/main/java")
        basePatchDirFile.resolve(cleanDirPath.get()).deleteRecursively()

        val patchSource = patchDir.pathOrNull ?: patchZip.path // used for error messages
        val rootPatchDir = patchDir.pathOrNull ?: patchZip.path.let { unzip(it, findOutputDir(it)) }

        try {
            if (!rootPatchDir.isDirectory()) {
                throw PaperweightException("Patch directory does not exist $patchSource")
            }

            val patchList = Files.walk(rootPatchDir).use { it.asSequence().filter { file -> file.isRegularFile() }.toSet() }
            if (patchList.isEmpty()) {
                throw PaperweightException("No patch files found in $patchSource")
            }

            // Copy in patch targets
            sourceJar.path.openZip().use { fs ->
                for (file in patchList) {
                    val javaName = javaFileName(rootPatchDir, file)
                    val out = basePatchDirFile.resolve(javaName)
                    val sourcePath = fs.getPath(javaName)

                    out.parent.createDirectories()
                    sourcePath.copyTo(out)
                }
            }

            // Apply patches
            for (file in patchList) {
                val javaName = javaFileName(rootPatchDir, file)

                if (printOutput.get()) {
                    println("Patching ${javaName.removeSuffix(".java")}")
                }

                val dirPrefix = basePatchDirFile.relativeTo(outputDir.path).invariantSeparatorsPathString
                git("apply", "--ignore-whitespace", "--directory=$dirPrefix", file.absolutePathString()).setupOut().execute()
            }
        } finally {
            if (rootPatchDir != patchDir.pathOrNull) {
                rootPatchDir.deleteRecursively()
            }
        }
    }

    private fun javaFileName(rootDir: Path, file: Path): String {
        return file.relativeTo(rootDir).toString().replaceAfterLast('.', "java")
    }

    private fun Command.setupOut() = apply {
        if (printOutput.get()) {
            setup(System.out, System.err)
        } else {
            setup(UselessOutputStream, UselessOutputStream)
        }
    }
}
