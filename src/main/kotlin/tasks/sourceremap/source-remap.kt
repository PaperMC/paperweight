package io.papermc.paperweight.tasks.sourceremap

import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.typedToJson
import io.papermc.paperweight.shared.PaperweightException
import io.papermc.paperweight.shared.RemapConfig
import io.papermc.paperweight.shared.RemapOps
import io.papermc.paperweight.shared.RemapOutput
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.redirect
import org.gradle.internal.jvm.Jvm
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object MercuryExecutor {

    fun exec(config: RemapConfig): RemapOutput {
        val mercuryJar = createTempFile("mercury", "jar")
        val configFile = createTempFile("mercury", "json")
        configFile.bufferedWriter().use { writer ->
            gson.toJson(config, writer)
        }

        try {
            MercuryExecutor::class.java.getResourceAsStream("/mercury-shadowed.jar").use { stream ->
                mercuryJar.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }

            val process = ProcessBuilder(
                Jvm.current().javaExecutable.canonicalPath,
                "-jar", mercuryJar.canonicalPath,
                configFile.canonicalPath
            ).start()

            val baos = ByteArrayOutputStream()
            redirect(process.inputStream, baos)
            redirect(process.errorStream, System.err)

            if (process.waitFor() != 0) {
                throw PaperweightException("Failed to execute Mercury")
            }

            val output = String(baos.toByteArray())
            val outputPath = Paths.get(output)
            try {
                return Files.newBufferedReader(outputPath).use { reader ->
                    gson.fromJson(reader)
                }
            } finally {
                Files.delete(outputPath)
            }
        } finally {
            mercuryJar.delete()
            configFile.delete()
        }
    }
}
