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
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class ClassNodeCacheImpl(
    private val jarFile: FileSystem,
    private val fallbackJars: List<FileSystem?>,
    private val fallbackDirectories: List<Path>? = null
) : ClassNodeCache {

    private val classNodeMap = hashMapOf<String, ClassNode?>()

    override fun findClass(name: String?): ClassNode? {
        if (name == null) {
            return null
        }
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
        for (fallbackJar in fallbackJars) {
            fallbackJar?.getPath(className)?.let { libraryClass ->
                if (libraryClass.exists()) {
                    return libraryClass.readBytes()
                }
            }
        }
        fallbackDirectories?.let { dirs ->
            for (path in dirs) {
                path.resolve(className).takeIf { it.exists() }
                    ?.let { libraryClass ->
                        if (libraryClass.exists()) {
                            return libraryClass.readBytes()
                        }
                    }
            }
        }
        return ClassLoader.getSystemResourceAsStream(className)?.readBytes() // JDK class
    }

    private fun normalize(name: String): String {
        val workingName = name.removeSuffix(".class")

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
