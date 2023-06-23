package io.papermc.paperweight.tasks

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.applyPatchesLock
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

abstract class ApplyFilePatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val patchFolder: DirectoryProperty

    @get:InputDirectory
    abstract val vanillaBase: DirectoryProperty

    @get:Input
    abstract val ignorePattern: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun runLocking() {
        val lockFile = layout.cache.resolve(applyPatchesLock(outputDir.path))
        acquireProcessLockWaiting(lockFile)
        try {
            run()
        } finally {
            lockFile.deleteForcefully()
        }
    }

    private fun run() {
        Git.checkForGit()

        println("  Cleanup")
        val vanillaBasePath = vanillaBase.path.resolve("src/vanilla")
        val vanillaOutputPath = outputDir.path.resolve("src/vanilla")
        vanillaOutputPath.deleteRecursively()

        println("  Applying file based patches...")

        val patchOp = PatchOperation.builder()
            .logTo(System.out)
            .basePath(vanillaBasePath)
            .outputPath(vanillaOutputPath)
            .patchesPath(patchFolder.path)
            .mode(PatchMode.EXACT)
            .verbose(false)
            .summary(true)
            .ignorePattern(ignorePattern.get())
            .build()
        patchOp.operate()

        println("  Copying git folder")
        vanillaBasePath.resolve(".git").copyRecursivelyTo(vanillaOutputPath.resolve(".git"))

        println("  Fixing assets")
        Files.writeString(vanillaOutputPath.resolve(".gitignore"), ".mcassetsroot")
        Files.createFile(vanillaOutputPath.resolve("assets/.mcassetsroot"))
        Files.createFile(vanillaOutputPath.resolve("data/.mcassetsroot"))

        Git(vanillaOutputPath).let { git ->
            git(*Git.add(false, ".")).run()
            git("commit", "-m", "Paper Core Patches", "--author=Paper Contributors <auto@mated.null>").run()

            git("tag", "-d", "core").runSilently(silenceErr = true)
            git("tag", "core").executeSilently(silenceErr = true)
        }
        println("  Done")
    }
}
