package io.papermc.paperweight.tasks

import codechicken.diffpatch.cli.DiffOperation
import io.papermc.paperweight.util.acquireProcessLockWaiting
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.constants.applyPatchesLock
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.path
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class RebuildFilePatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val vanillaBase: DirectoryProperty

    @get:InputDirectory
    abstract val serverDir: DirectoryProperty

    @get:Input
    abstract val ignorePattern: Property<String>

    @get:OutputDirectory
    abstract val patchFolder: DirectoryProperty

    @TaskAction
    fun runLocking() {
        val lockFile = layout.cache.resolve(applyPatchesLock(serverDir.path))
        acquireProcessLockWaiting(lockFile)
        try {
            run()
        } finally {
            lockFile.deleteForcefully()
        }
    }

    private fun run() {
        val vanillaBasePath = vanillaBase.path.resolve("src/vanilla")
        val vanillaPatchedPath = serverDir.path.resolve("src/vanilla")

        val patchOp = DiffOperation.builder()
            .logTo(System.out)
            .aPath(vanillaBasePath)
            .bPath(vanillaPatchedPath)
            .outputPath(patchFolder.path)
            .verbose(false)
            .summary(true)
            .lineEnding("\n")
            .ignorePattern(ignorePattern.get())
            .build()

        patchOp.operate()
    }
}
