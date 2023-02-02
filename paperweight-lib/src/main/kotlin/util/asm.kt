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

package io.papermc.paperweight.util

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

object SyntheticUtil : AsmUtil {

    fun findBaseMethod(node: MethodNode, className: String, methods: List<MethodNode> = emptyList()): MethodDesc {
        if (node.access !in Opcodes.ACC_SYNTHETIC) {
            return MethodDesc(node.name, node.desc)
        }

        return checkMethodNode(node, className, methods) ?: MethodDesc(node.name, node.desc)
    }

    private enum class State {
        IN_PARAMS,
        INVOKE,
        RETURN,
        OTHER_INSN
    }

    // This tries to match the behavior of SpecialSource2's SyntheticFinder.addSynthetics() method
    private fun checkMethodNode(node: MethodNode, className: String, methods: List<MethodNode>): MethodDesc? {
        var state = State.IN_PARAMS
        var nextLvt = 0

        var invokeInsn: MethodInsnNode? = null

        for (insn in node.instructions) {
            if (insn is LabelNode || insn is LineNumberNode || insn is TypeInsnNode) {
                continue
            }

            if (state == State.IN_PARAMS) {
                if (insn !is VarInsnNode || insn.`var` != nextLvt) {
                    state = State.INVOKE
                }
            }

            when (state) {
                State.IN_PARAMS -> {
                    nextLvt++
                    if (insn.opcode == Opcodes.LLOAD || insn.opcode == Opcodes.DLOAD) {
                        nextLvt++
                    }
                }
                State.INVOKE -> {
                    // Must be a virtual or interface invoke instruction
                    if ((insn.opcode != Opcodes.INVOKEVIRTUAL && insn.opcode != Opcodes.INVOKEINTERFACE) || insn !is MethodInsnNode) {
                        return null
                    }

                    invokeInsn = insn
                    state = State.RETURN
                }
                State.RETURN -> {
                    // The next instruction must be a return
                    if (insn.opcode !in Opcodes.IRETURN..Opcodes.RETURN) {
                        return null
                    }

                    state = State.OTHER_INSN
                }
                State.OTHER_INSN -> {
                    // We shouldn't see any other instructions
                    return null
                }
            }
        }

        val invoke = invokeInsn ?: return null

        // Must be a method in the same class with a different signature
        if (className != invoke.owner || (node.name == invoke.name && node.desc == invoke.desc)) {
            return null
        }

        // The descriptors need to be the same size
        if (Type.getArgumentTypes(node.desc).size != Type.getArgumentTypes(invoke.desc).size) {
            return null
        }

        for (otherMethod in methods) {
            if (node.name == otherMethod.name && invoke.desc == otherMethod.desc && otherMethod.signature != null) {
                return null
            }
        }

        // Add this method as a synthetic accessor for insn.name
        return MethodDesc(invoke.name, invoke.desc)
    }
}

data class MethodDesc(val name: String, val desc: String)

interface AsmUtil {
    companion object {
        const val RESET_ACCESS: Int = (Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()
    }

    operator fun Int.contains(value: Int): Boolean {
        return value and this != 0
    }
}
