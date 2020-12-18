package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.zip
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class CopyResources : BaseTask() {
    @get:InputFile
    abstract val inputJar: RegularFileProperty
    @get:InputFile
    abstract val vanillaJar: RegularFileProperty
    @get:Input
    abstract val includes: ListProperty<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputJar.file
        val target = out.resolveSibling("${out.name}.dir")
        target.mkdirs()

        fs.copy {
            from(archives.zipTree(vanillaJar)) {
                for (inc in this@CopyResources.includes.get()) {
                    include(inc)
                }
            }
            into(target)
            from(archives.zipTree(inputJar))
            into(target)
        }

        zip(target, outputJar)
        target.deleteRecursively()
    }
}
