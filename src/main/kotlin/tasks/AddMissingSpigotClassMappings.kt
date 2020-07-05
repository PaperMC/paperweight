package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

open class AddMissingSpigotClassMappings : DefaultTask() {

    @InputFile
    val classSrg: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val memberSrg: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val missingClassEntriesSrg: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val missingMemberEntriesSrg: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val outputClassSrg: RegularFileProperty = defaultOutput("class.csrg")
    @OutputFile
    val outputMemberSrg: RegularFileProperty = defaultOutput("member.csrg")

    @TaskAction
    fun run() {
        addLines(classSrg.file, missingClassEntriesSrg.file, outputClassSrg.file)
        addLines(memberSrg.file, missingMemberEntriesSrg.file, outputMemberSrg.file)
    }

    private fun addLines(inFile: File, appendFile: File, outputFile: File) {
        val lines = mutableListOf<String>()
        inFile.bufferedReader().use { reader ->
            lines.addAll(reader.readLines())
        }
        appendFile.bufferedReader().use { reader ->
            lines.addAll(reader.readLines())
        }
        lines.sort()
        outputFile.bufferedWriter().use { writer ->
            lines.forEach(writer::appendln)
        }
    }
}
