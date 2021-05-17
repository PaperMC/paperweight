/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.AsmUtil
import io.papermc.paperweight.util.SyntheticUtil
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.walk
import io.papermc.paperweight.util.writeZip
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.*
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
        vanillaJar.path.openZip().use { vanillaJar ->
            outputJar.path.writeZip().use { out ->
                inputJar.path.openZip().use { jarFile ->
                    processJars(jarFile, vanillaJar, out)
                }
            }
        }
    }

    private fun processJars(jarFile: FileSystem, fallbackJar: FileSystem, output: FileSystem) {
        val classNodeCache = ClassNodeCache(jarFile, fallbackJar)

        jarFile.walk().use { stream ->
            stream.forEach { file ->
                processFile(file, output, classNodeCache)
            }
        }
    }

    private fun processFile(file: Path, output: FileSystem, classNodeCache: ClassNodeCache) {
        val outFile = output.getPath(file.absolutePathString())

        if (file.isDirectory()) {
            outFile.createDirectories()
            return
        }

        if (!file.name.endsWith(".class")) {
            file.copyTo(outFile)
            return
        }

        processClassFile(file, outFile, classNodeCache)
    }

    private fun processClassFile(file: Path, outFile: Path, classNodeCache: ClassNodeCache) {
        val node = classNodeCache.findClass(file.toString()) ?: error("No ClassNode found for known entry: ${file.name}")

        ParameterAnnotationFixer(node).visitNode()
        OverrideAnnotationAdder(node, classNodeCache).visitNode()

        val writer = ClassWriter(0)
        node.accept(writer)

        outFile.writeBytes(writer.toByteArray())
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

class ClassNodeCache(private val jarFile: FileSystem, private val fallbackJar: FileSystem) {

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
        jarFile.getPath(className).let { remappedClass ->
            if (remappedClass.exists()) {
                return remappedClass.readBytes()
            }
        }
        fallbackJar.getPath(className).let { libraryClass ->
            if (libraryClass.exists()) {
                return libraryClass.readBytes()
            }
        }
        return ClassLoader.getSystemResourceAsStream(className)?.readBytes() // JDK class
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
}
