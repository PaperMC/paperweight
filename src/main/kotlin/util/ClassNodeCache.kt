package io.papermc.paperweight.util

import java.util.jar.JarFile
import java.util.zip.ZipEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class ClassNodeCache(private val jarFile: JarFile, private val fallbackJar: JarFile? = null) {

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
        return (
                jarFile.getInputStream(entry) // remapped class
                    ?: fallbackJar?.getInputStream(entry) // library class
                    ?: ClassLoader.getSystemResourceAsStream(className) // JDK class
                )?.use { it.readBytes() }
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
