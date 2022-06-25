/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.util

import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.*
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

object JarProcessing {
    interface ClassProcessor {
        fun shouldProcess(file: Path): Boolean = true

        interface NodeBased : ClassProcessor {
            fun processClass(node: ClassNode, classNodeCache: ClassNodeCache)
        }

        interface VisitorBased : ClassProcessor {
            fun processClass(node: ClassNode, parent: ClassVisitor, classNodeCache: ClassNodeCache): ClassVisitor?
        }
    }

    fun processJar(
        jarFile: FileSystem,
        output: FileSystem,
        processor: ClassProcessor
    ) = processJar(jarFile, null, output, processor)

    fun processJar(
        jarFile: FileSystem,
        fallbackJar: FileSystem?,
        output: FileSystem,
        processor: ClassProcessor
    ) {
        val classNodeCache = ClassNodeCache.create(jarFile, fallbackJar)

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

        val writer = ClassWriter(0)
        val visitor = when (processor) {
            is ClassProcessor.VisitorBased -> processor.processClass(node, writer, classNodeCache) ?: writer
            is ClassProcessor.NodeBased -> {
                processor.processClass(node, classNodeCache)
                writer
            }
            else -> error("Unknown class processor type: ${processor::class.java.name}")
        }
        node.accept(visitor)
        outFile.writeBytes(writer.toByteArray())
    }
}
