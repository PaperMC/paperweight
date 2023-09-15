/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.NavigableMap
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.collections.set
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

@CacheableTask
abstract class LineMapJar : JavaLauncherTask() {
    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Classpath
    abstract val decompiledJar: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmArgs.convention(listOf("-Xmx512m"))
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        lineMapJar(
            workerExecutor = workerExecutor,
            jvmArgs = jvmArgs.get(),
            launcher = launcher.get(),
            inputJarPath = inputJar.path,
            outputJarPath = outputJar.path,
            decompileJarPath = decompiledJar.path
        )
    }
}

fun lineMapJar(
    workerExecutor: WorkerExecutor,
    jvmArgs: List<String> = arrayListOf("-Xmx512m"),
    launcher: JavaLauncher,
    inputJarPath: Path,
    outputJarPath: Path,
    decompileJarPath: Path,
): WorkQueue {
    ensureParentExists(outputJarPath)
    ensureDeleted(outputJarPath)

    val queue = workerExecutor.processIsolation {
        forkOptions.jvmArgs(jvmArgs)
        forkOptions.executable(launcher.executablePath.path.absolutePathString())
    }

    queue.submit(LineMapJarAction::class) {
        inputJar.set(inputJarPath)
        outputJar.set(outputJarPath)
        decompileJar.set(decompileJarPath)
    }

    return queue
}

private abstract class LineMapJarAction : WorkAction<LineMapJarAction.Parameters> {
    interface Parameters : WorkParameters {
        val inputJar: RegularFileProperty
        val outputJar: RegularFileProperty
        val decompileJar: RegularFileProperty
    }

    override fun execute() {
        val lineMap = readLineMap(parameters.decompileJar.path)
        parameters.outputJar.path.writeZip().use { out ->
            parameters.inputJar.path.openZip().use { jarFile ->
                JarProcessing.processJar(jarFile, out, LineMappingClassProcessor(lineMap))
            }
        }
    }

    class LineMappingClassProcessor(private val lineMap: Map<String, NavigableMap<Int, Int>>) : JarProcessing.ClassProcessor.VisitorBased {
        override fun processClass(node: ClassNode, parent: ClassVisitor, classNodeCache: ClassNodeCache): ClassVisitor? {
            val map = lineMap[node.name.substringBefore('$')]
                ?: return null // No line maps for class?
            return LineMappingVisitor(parent, map)
        }

        override fun shouldProcess(file: Path): Boolean {
            val name = file.toString()
                .substring(1) // remove leading /
                .substringBefore(".class")
                .substringBefore('$')
            return name in lineMap
        }
    }
}

private class LineMappingVisitor(
    parent: ClassVisitor?,
    private val lineMapping: NavigableMap<Int, Int>
) : ClassVisitor(Opcodes.ASM9, parent) {
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor =
        MethodLineFixer(super.visitMethod(access, name, descriptor, signature, exceptions), lineMapping)

    private class MethodLineFixer(
        parent: MethodVisitor?,
        private val lineMapping: NavigableMap<Int, Int>
    ) : MethodVisitor(Opcodes.ASM9, parent) {
        override fun visitLineNumber(line: Int, start: Label?) {
            var mapped = lineMapping[line]
            if (mapped == null) {
                val entry = lineMapping.ceilingEntry(line)
                if (entry != null) {
                    mapped = entry.value
                }
            }
            super.visitLineNumber(mapped ?: line, start)
        }
    }
}

private fun readLineMap(decompileJar: Path): Map<String, NavigableMap<Int, Int>> {
    val classes: MutableMap<String, NavigableMap<Int, Int>> = HashMap()
    try {
        decompileJar.inputStream().use { fis ->
            ZipInputStream(fis).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val extra: ByteArray? = entry.extra
                    if (extra == null || !entry.name.endsWith(".java")) {
                        entry = zip.nextEntry
                        continue
                    }
                    val buf: ByteBuffer = ByteBuffer.wrap(extra)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    while (buf.hasRemaining()) {
                        val id: Short = buf.short
                        val len: Short = buf.short
                        if (id.toInt() == 0x4646) { // FF
                            val cls: String = entry.name.substring(0, entry.name.length - 5)
                            val ver: Byte = buf.get()
                            if (ver != 1.toByte()) {
                                throw PaperweightException("Wrong FF code line version for " + entry.name + " (got $ver, expected 1)")
                            }
                            val count = (len - 1) / 4
                            val lines: NavigableMap<Int, Int> = TreeMap()
                            for (x in 0 until count) {
                                val oldLine: Int = buf.short.toInt()
                                val newLine: Int = buf.short.toInt()
                                lines[oldLine] = newLine
                            }
                            classes[cls] = lines
                        } else {
                            buf.position(buf.position() + len)
                        }
                    }

                    entry = zip.nextEntry
                }
            }
        }
    } catch (ex: Exception) {
        throw PaperweightException("Could not read line maps from decompiled jar: $decompileJar", ex)
    }
    return classes
}
