/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.util.AsmUtil
import io.papermc.paperweight.util.SyntheticUtil
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

abstract class FixJar : BaseTask(), AsmUtil {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        JarFile(vanillaJar.file).use { vanillaJar ->
            JarOutputStream(outputJar.file.outputStream()).use { out ->
                JarFile(inputJar.file).use { jarFile ->
                    val classNodeCache = ClassNodeCache(jarFile, vanillaJar)

                    for (entry in jarFile.entries()) {
                        if (!entry.name.endsWith(".class")) {
                            out.putNextEntry(entry)
                            try {
                                jarFile.getInputStream(entry).copyTo(out)
                            } finally {
                                out.closeEntry()
                            }
                            continue
                        }

                        try {
                            val node =
                                classNodeCache.findClass(entry.name) ?: error("No ClassNode found for known entry")

                            ParameterAnnotationFixer(node).visitNode()
                            OverrideAnnotationAdder(node, classNodeCache).visitNode()

                            val writer = ClassWriter(0)
                            node.accept(writer)

                            out.putNextEntry(ZipEntry(entry.name))
                            out.write(writer.toByteArray())
                            out.flush()
                        } finally {
                            out.closeEntry()
                        }
                    }

                    classNodeCache.clear()
                }
            }
        }

    }
}

/*
 * This was adapted from code originally written by Pokechu22 in MCInjector
 * Link: https://github.com/ModCoderPack/MCInjector/pull/3
 */
class ParameterAnnotationFixer(private val node: ClassNode) : AsmUtil {

    fun visitNode() {
        val expected = expectedSyntheticParams() ?: return

        for (method in node.methods) {
            if (method.name == "<init>") {
                processConstructor(method, expected)
            }
        }
    }

    private fun expectedSyntheticParams(): List<Type>? {
        if (Opcodes.ACC_ENUM in node.access) {
            return listOf(Type.getObjectType("java/lang/String"), Type.INT_TYPE)
        }

        val innerNode = node.innerClasses.firstOrNull { it.name == node.name } ?: return null
        if (innerNode.innerName == null || (Opcodes.ACC_STATIC or Opcodes.ACC_INTERFACE) in innerNode.access) {
            return null
        }

        return listOf(Type.getObjectType(innerNode.outerName))
    }

    private fun processConstructor(method: MethodNode, synthParams: List<Type>) {
        val params = Type.getArgumentTypes(method.desc).asList()

        if (!params.beginsWith(synthParams)) {
            return
        }

        method.visibleParameterAnnotations = process(params.size, synthParams.size, method.visibleParameterAnnotations)
        method.invisibleParameterAnnotations =
            process(params.size, synthParams.size, method.invisibleParameterAnnotations)

        method.visibleParameterAnnotations?.let {
            method.visibleAnnotableParameterCount = it.size
        }
        method.invisibleParameterAnnotations?.let {
            method.invisibleAnnotableParameterCount = it.size
        }
    }

    private fun process(
        paramCount: Int,
        synthCount: Int,
        annotations: Array<List<AnnotationNode>>?
    ): Array<List<AnnotationNode>>? {
        if (annotations == null) {
            return null
        }
        if (paramCount == annotations.size) {
            return annotations.copyOfRange(synthCount, paramCount)
        }
        return annotations
    }

    private fun <T> List<T>.beginsWith(other: List<T>): Boolean {
        if (this.size < other.size) {
            return false
        }
        for (i in other.indices) {
            if (this[i] != other[i]) {
                return false
            }
        }
        return true
    }
}

class OverrideAnnotationAdder(private val node: ClassNode, private val classNodeCache: ClassNodeCache) : AsmUtil {

    fun visitNode() {
        val superMethods = collectSuperMethods(node)

        val disqualifiedMethods = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
        for (method in node.methods) {
            if (method.access in disqualifiedMethods) {
                continue
            }

            if (method.name == "<init>" || method.name == "<clinit>") {
                continue
            }
            val (name, desc) = SyntheticUtil.findBaseMethod(method, node.name)

            if (method.name + method.desc in superMethods) {
                val targetMethod = node.methods.firstOrNull { it.name == name && it.desc == desc } ?: method

                if (targetMethod.invisibleAnnotations == null) {
                    targetMethod.invisibleAnnotations = arrayListOf()
                }
                val annoClass = "Ljava/lang/Override;"
                if (targetMethod.invisibleAnnotations.none { it.desc == annoClass }) {
                    targetMethod.invisibleAnnotations.add(AnnotationNode(annoClass))
                }
            }
        }
    }

    private fun collectSuperMethods(node: ClassNode): Set<String> {
        fun collectSuperMethods(node: ClassNode, superMethods: HashSet<String>) {
            val supers = listOfNotNull(node.superName, *node.interfaces.toTypedArray())
            if (supers.isEmpty()) {
                return
            }

            val disqualifiedMethods = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
            val superNodes = supers.mapNotNull { classNodeCache.findClass(it) }
            superNodes.asSequence()
                .flatMap { classNode -> classNode.methods.asSequence() }
                .filter { method -> method.access !in disqualifiedMethods }
                .filter { method -> method.name != "<init>" && method.name != "<clinit>" }
                .map { method -> method.name + method.desc }
                .toCollection(superMethods)

            for (superNode in superNodes) {
                collectSuperMethods(superNode, superMethods)
            }
        }

        val result = hashSetOf<String>()
        collectSuperMethods(node, result)
        return result
    }
}

class ClassNodeCache(private val jarFile: JarFile, private val fallbackJar: JarFile) {

    private val classNodeMap = hashMapOf<String, ClassNode?>()

    fun findClass(name: String): ClassNode? {
        return classNodeMap.computeIfAbsent(normalize(name)) { fileName ->
            val classData = findClassData(fileName) ?: return@computeIfAbsent null
            val classReader = ClassReader(classData)
            val node = ClassNode(Opcodes.ASM9)
            classReader.accept(node, 0)
            return@computeIfAbsent node
        }
    }

    private fun findClassData(className: String): ByteArray? {
        val entry = ZipEntry(className)
        return (jarFile.getInputStream(entry) // remapped class
            ?: fallbackJar.getInputStream(entry) // library class
            ?: ClassLoader.getSystemResourceAsStream(className))?.use { it.readBytes() } // JDK class
    }

    private fun normalize(name: String): String {
        var workingName = name
        if (workingName.endsWith(".class")) {
            workingName = workingName.substring(0, workingName.length - 6)
        }

        var startIndex = 0
        var endIndex = workingName.length
        if (workingName.startsWith('L')) {
            startIndex = 1
        }
        if (workingName.endsWith(';')) {
            endIndex--
        }

        return workingName.substring(startIndex, endIndex).replace('.', '/') + ".class"
    }

    fun clear() {
        classNodeMap.clear()
    }
}
