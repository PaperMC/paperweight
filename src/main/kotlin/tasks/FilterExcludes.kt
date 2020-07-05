package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.file
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import java.io.File

/**
 * Because Spigot doesn't remap all classes, there are class and package name clashes if we don't do this in the source
 * remap step. Other than that, we don't need this jar
 */
open class FilterExcludes : ZippedTask() {

    @InputFile
    val excludesFile: RegularFileProperty = project.objects.fileProperty()

    override fun run(rootDir: File) {
        excludesFile.file.useLines { lines ->
            for (line in lines) {
                if (line.startsWith('#') || line.isBlank()) {
                    continue
                }
                val file = if (line.contains('/')) {
                    rootDir.resolve("$line.class")
                } else {
                    rootDir.resolve("net/minecraft/server/$line.class")
                }
                file.delete()
            }
        }
    }
}
