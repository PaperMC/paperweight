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

    fun findBaseMethod(node: MethodNode, className: String): MethodDesc {
        if (node.access !in Opcodes.ACC_SYNTHETIC) {
            return MethodDesc(node.name, node.desc)
        }

        return checkMethodNode(node, className) ?: MethodDesc(node.name, node.desc)
    }

    private enum class State {
        IN_PARAMS,
        INVOKE,
        RETURN,
        OTHER_INSN
    }

    // This tries to match the behavior of SpecialSource2's SyntheticFinder.addSynthetics() method
    private fun checkMethodNode(node: MethodNode, className: String): MethodDesc? {
        var state = State.IN_PARAMS
        var nextLvt = 0

        var invokeInsn: MethodInsnNode? = null

        loop@for (insn in node.instructions) {
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

        // Add this method as a synthetic accessor for insn.name
        return MethodDesc(invoke.name, invoke.desc)
    }
}

data class MethodDesc(val name: String, val desc: String)
