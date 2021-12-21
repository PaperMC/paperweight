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

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

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
        if (innerNode.outerName == null) {
            // method local class/other complex case
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
