package io.papermc.paperweight.util

import io.papermc.paperweight.PaperweightException
import java.io.File
import java.util.zip.ZipFile

object McDev {

    fun importMcDev(patches: Array<File>, decompJar: File, libraryImports: File, libraryDir: File, targetDir: File) {
        val importMcDev = readMcDevNames(patches).asSequence()
            .map { targetDir.resolve("net/minecraft/server/$it.java") }
            .filter { !it.exists() }
            .toSet()

        ZipFile(decompJar).use { zipFile ->
            for (file in importMcDev) {
                val zipEntry = zipFile.getEntry(file.relativeTo(targetDir).path) ?: continue
                zipFile.getInputStream(zipEntry).use { input ->
                    file.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        // Import library classes
        val libraryLines = libraryImports.readLines()
        if (libraryLines.isEmpty()) {
            return
        }

        val libFiles = (libraryDir.listFiles() ?: emptyArray()).filter { it.name.endsWith("-sources.jar" )}
        if (libFiles.isEmpty()) {
            throw PaperweightException("No library files found")
        }

        for (line in libraryLines) {
            val (libraryName, filePath) = line.split(" ")
            val libFile = libFiles.firstOrNull { it.name.startsWith(libraryName) }
                ?: throw PaperweightException("Failed to find library: $libraryName for class $filePath")

            val outputFile = targetDir.resolve(filePath)
            if (outputFile.exists()) {
                continue
            }
            outputFile.parentFile.mkdirs()
            ZipFile(libFile).use { zipFile ->
                val zipEntry = zipFile.getEntry(filePath)
                zipFile.getInputStream(zipEntry).use { input ->
                    outputFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun readMcDevNames(patches: Array<File>): Set<String> {
        val result = hashSetOf<String>()

        val prefix = "+++ b/src/main/java/net/minecraft/server/"
        val suffix = ".java"

        for (patch in patches) {
            patch.useLines { lines ->
                lines.filter { it.startsWith(prefix) }
                    .mapTo(result) { it.substring(prefix.length, it.length - suffix.length) }
            }
        }

        return result
    }
}
