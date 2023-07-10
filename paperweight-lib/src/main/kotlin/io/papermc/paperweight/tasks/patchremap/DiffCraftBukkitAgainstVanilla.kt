package io.papermc.paperweight.tasks.patchremap

import codechicken.diffpatch.cli.DiffOperation
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.filesMatchingRecursive
import io.papermc.paperweight.util.path
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

abstract class DiffCraftBukkitAgainstVanilla: BaseTask() {

    @get:InputFile
    abstract val craftBukkit: RegularFileProperty

    @get:InputDirectory
    abstract val vanilla: DirectoryProperty

    @get:OutputDirectory
    abstract val patches: RegularFileProperty

    override fun init() {
        patches.convention(defaultOutput())
    }

    @TaskAction
    open fun run() {
        val diffOp = DiffOperation.builder()
            .logTo(System.out)
            .aPath(vanilla.path.resolve("src/vanilla"))
            .bPath(craftBukkit.path)
            .outputPath(patches.path.resolve("dum"))
            .verbose(false)
            .summary(true)
            .lineEnding("\n")
            //.ignorePattern(ignorePattern.get())
            .build()

        diffOp.operate()

        patches.path.resolve("dum").filesMatchingRecursive("*.patch").forEach { p ->
            if (Files.readAllLines(p)[1].contains("+++ /dev/null")) {
                Files.delete(p)
            }
        }
        Files.walk(patches.path.resolve("dum"))
            .filter { Files.isDirectory(it) }
            .filter { it.toFile().listFiles()?.isEmpty() ?: false }
            .forEach { Files.delete(it) }
    }
}
