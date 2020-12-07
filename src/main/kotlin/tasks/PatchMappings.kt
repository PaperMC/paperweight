package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import java.nio.file.Files
import net.fabricmc.lorenztiny.TinyMappingFormat
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PatchMappings : DefaultTask() {

    @get:InputFile
    abstract val inputMappings: RegularFileProperty
    @get:InputFile
    @get:Optional
    abstract val patchMappings: RegularFileProperty

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    @TaskAction
    fun run() {
        val mappings = TinyMappingFormat.STANDARD.read(inputMappings.path, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)
        patchMappings.pathOrNull?.let { patchFile ->
            val temp = Files.createTempFile("patch", "tiny")
            try {
                val comment = Regex("\\s*#.*")
                // tiny format doesn't allow comments, so we manually remove them
                // The tiny mappings reader also doesn't have a InputStream or Reader input...
                Files.newBufferedReader(patchFile).useLines { lines ->
                    Files.newBufferedWriter(temp).use { writer ->
                        for (line in lines) {
                            val newLine = comment.replace(line, "")
                            if (newLine.isNotBlank()) {
                                writer.appendln(newLine)
                            }
                        }
                    }
                }
                TinyMappingFormat.STANDARD.read(mappings, temp, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        TinyMappingFormat.STANDARD.write(mappings, outputMappings.path, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)
    }
}
