package io.papermc.paperweight.util

import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

object FixJar {
    interface ClassProcessor {
        fun shouldProcess(file: Path): Boolean = true
        fun processClass(node: ClassNode, classNodeCache: ClassNodeCache)
    }

    fun processJars(
        jarFile: FileSystem,
        output: FileSystem,
        processor: ClassProcessor
    ) = processJars(jarFile, null, output, processor)

    fun processJars(
        jarFile: FileSystem,
        fallbackJar: FileSystem?,
        output: FileSystem,
        processor: ClassProcessor
    ) {
        val classNodeCache = ClassNodeCache(jarFile, fallbackJar)

        jarFile.walk().use { stream ->
            stream.forEach { file ->
                processFile(file, output, classNodeCache, processor)
            }
        }
    }

    private fun processFile(file: Path, output: FileSystem, classNodeCache: ClassNodeCache, processor: ClassProcessor) {
        val outFile = output.getPath(file.absolutePathString())

        if (file.isDirectory()) {
            outFile.createDirectories()
            return
        }

        if (!file.name.endsWith(".class")) {
            file.copyTo(outFile)
            return
        }

        if (processor.shouldProcess(file)) {
            processClass(file, outFile, classNodeCache, processor)
        } else {
            file.copyTo(outFile)
        }
    }

    private fun processClass(file: Path, outFile: Path, classNodeCache: ClassNodeCache, processor: ClassProcessor) {
        val node = classNodeCache.findClass(file.toString()) ?: error("No ClassNode found for known entry: ${file.name}")

        processor.processClass(node, classNodeCache)

        val writer = ClassWriter(0)
        node.accept(writer)

        outFile.writeBytes(writer.toByteArray())
    }
}
