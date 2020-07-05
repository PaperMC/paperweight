package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.toProvider
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

open class AddMissingSpigotClassMappings : DefaultTask() {

    @InputFile
    val inputSrg = project.objects.fileProperty()

    @InputFile
    val missingEntriesSrg = project.objects.fileProperty()

    @OutputFile
    val outputSrg = project.objects.run {
        fileProperty().convention(project.toProvider(project.cache.resolve(paperTaskOutput("csrg"))))
    }

    @TaskAction
    fun run() {
        val lines = mutableListOf<String>()
        inputSrg.file.bufferedReader().use { reader ->
            lines.addAll(reader.readLines())
        }
        missingEntriesSrg.file.bufferedReader().use { reader ->
            lines.addAll(reader.readLines())
        }
        lines.sort()
        outputSrg.file.bufferedWriter().use { writer ->
            lines.forEach(writer::appendln)
        }
    }
}
