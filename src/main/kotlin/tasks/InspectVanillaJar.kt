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
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

abstract class InspectVanillaJar : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val loggerFile: RegularFileProperty

    @get:OutputFile
    abstract val paramIndexes: RegularFileProperty

    @get:OutputFile
    abstract val syntheticMethods: RegularFileProperty

    override fun init() {
        loggerFile.convention(defaultOutput("$name-loggerFields", "txt"))
        paramIndexes.convention(defaultOutput("$name-paramIndexes", "txt"))
        syntheticMethods.convention(defaultOutput("$name-syntheticMethods", "txt"))
    }

    @TaskAction
    fun run() {
        val loggers = mutableListOf<LoggerFields.Data>()
        val params = mutableListOf<ParamIndexes.Data>()
        val synthMethods = mutableListOf<SyntheticMethods.Data>()

        var visitor: ClassVisitor
        visitor = LoggerFields.Visitor(null, loggers)
        visitor = ParamIndexes.Visitor(visitor, params)
        visitor = SyntheticMethods.Visitor(visitor, synthMethods)

        archives.zipTree(inputJar.file).matching {
            include("/*.class")
            include("/net/minecraft/**/*.class")
        }.forEach { file ->
            if (file.isDirectory) {
                return@forEach
            }
            val classData = file.readBytes()

            ClassReader(classData).accept(visitor, 0)
        }

        loggerFile.file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (loggerField in loggers) {
                writer.append(loggerField.className)
                writer.append(' ')
                writer.append(loggerField.fieldName)
                writer.newLine()
            }
        }

        paramIndexes.file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (methodData in params) {
                writer.append(methodData.className)
                writer.append(' ')
                writer.append(methodData.methodName)
                writer.append(' ')
                writer.append(methodData.methodDescriptor)
                for (target in methodData.params) {
                    writer.append(' ')
                    writer.append(target.binaryIndex.toString())
                    writer.append(' ')
                    writer.append(target.sourceIndex.toString())
                }
                writer.newLine()
            }
        }

        syntheticMethods.file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for ((className, desc, synthName, baseName) in synthMethods) {
                writer.append(className)
                writer.append(' ')
                writer.append(desc)
                writer.append(' ')
                writer.append(synthName)
                writer.append(' ')
                writer.append(baseName)
                writer.newLine()
            }
        }
    }
}

abstract class BaseClassVisitor(classVisitor: ClassVisitor?) : ClassVisitor(Opcodes.ASM8, classVisitor), AsmUtil {
    protected var currentClass: String? = null

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.currentClass = name
        super.visit(version, access, name, signature, superName, interfaces)
    }
}

/*
 * SpecialSource2 automatically maps all Logger fields to the name LOGGER, without needing mappings defined, so we need
 * to make a note of all of those fields
 */
class LoggerFields {
    class Visitor(
        classVisitor: ClassVisitor?,
        private val fields: MutableList<Data>
    ) : BaseClassVisitor(classVisitor) {

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            val ret = super.visitField(access, name, descriptor, signature, value)
            val className = currentClass ?: return ret

            if (Opcodes.ACC_STATIC !in access || Opcodes.ACC_FINAL !in access)  {
                return ret
            }
            if (descriptor != "Lorg/apache/logging/log4j/Logger;") {
                return ret
            }
            fields += Data(className, name)
            return ret
        }
    }

    data class Data(val className: String, val fieldName: String)
}

/*
 * Source-remapping uses 0-based param indexes, but the param indexes we have are LVT-based. We have to look at the
 * actual bytecode to translate the LVT-based indexes back to 0-based param indexes.
 */
class ParamIndexes {
    class Visitor(
        classVisitor: ClassVisitor?,
        private val methods: MutableList<Data>
    ) : BaseClassVisitor(classVisitor) {

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            val ret = super.visitMethod(access, name, descriptor, signature, exceptions)
            val className = currentClass ?: return ret

            val isStatic = access and Opcodes.ACC_STATIC != 0
            var currentIndex = if (isStatic) 0 else 1

            val types = Type.getArgumentTypes(descriptor)
            if (types.isEmpty()) {
                return ret
            }

            val params = ArrayList<ParamTarget>(types.size)
            val data = Data(className, name, descriptor, params)
            methods += data

            for (i in types.indices) {
                params += ParamTarget(currentIndex, i)
                currentIndex++

                // Figure out if we should skip the next index
                val type = types[i]
                if (type === Type.LONG_TYPE || type === Type.DOUBLE_TYPE) {
                    currentIndex++
                }
            }

            return ret
        }
    }

    data class Data(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
        val params: List<ParamTarget>
    )

    data class ParamTarget(val binaryIndex: Int, val sourceIndex: Int)
}

/*
 * SpecialSource2 automatically handles certain synthetic method renames, which leads to methods which don't match any
 * existing mapping. We need to make a note of all of the synthetic methods which match SpecialSource2's checks so we
 * can handle it in our generated mappings.
 */
class SyntheticMethods {
    class Visitor(
        classVisitor: ClassVisitor?,
        private val methods: MutableList<Data>
    ) : BaseClassVisitor(classVisitor) {

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            val ret = super.visitMethod(access, name, descriptor, signature, exceptions)
            val className = currentClass ?: return ret

            if (Opcodes.ACC_SYNTHETIC !in access || Opcodes.ACC_BRIDGE in access || name.contains('$')) {
                return ret
            }

            return SynthMethodVisitor(access, name, descriptor, signature, exceptions, className, methods)
        }
    }

    private class SynthMethodVisitor(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
        private val className: String,
        private val methods: MutableList<Data>
    ) : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {

        private enum class State {
            IN_PARAMS,
            INVOKE,
            RETURN,
            OTHER_INSN
        }

        // This tries to match the behavior of SpecialSource2's SyntheticFinder.addSynthetics() method
        override fun visitEnd() {
            var state = State.IN_PARAMS
            var nextLvt = 0

            var invokeInsn: MethodInsnNode? = null

            loop@for (insn in instructions) {
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
                            return
                        }

                        invokeInsn = insn
                        state = State.RETURN
                    }
                    State.RETURN -> {
                        // The next instruction must be a return
                        if (insn.opcode !in Opcodes.IRETURN..Opcodes.RETURN) {
                            return
                        }

                        state = State.OTHER_INSN
                    }
                    State.OTHER_INSN -> {
                        // We shouldn't see any other instructions
                        return
                    }
                }
            }

            val invoke = invokeInsn ?: return

            // Must be a method in the same class with a different signature
            if (className != invoke.owner || name == invoke.name || desc == invoke.desc) {
                return
            }

            // The descriptors need to be the same size
            if (Type.getArgumentTypes(desc).size != Type.getArgumentTypes(invoke.desc).size) {
                return
            }

            // Add this method as a synthetic accessor for insn.name
            methods += Data(className, invoke.desc, name, invoke.name)
        }
    }

    data class Data(val className: String, val desc: String, val synthName: String, val baseName: String)
}
