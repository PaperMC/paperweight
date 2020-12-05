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

abstract class InspectVanillaJar : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val loggerFile: RegularFileProperty

    @get:OutputFile
    abstract val paramIndexes: RegularFileProperty

    override fun init() {
        loggerFile.convention(defaultOutput("$name-loggerFields", "txt"))
        paramIndexes.convention(defaultOutput("$name-paramIndexes", "txt"))
    }

    @TaskAction
    fun run() {
        val loggers = mutableListOf<LoggerField>()
        val params = mutableListOf<MethodData>()

        var visitor: ClassVisitor
        visitor = LoggerFinder(null, loggers)
        visitor = ParamIndexInspector(visitor, params)

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
    }
}

abstract class BaseClassVisitor(classVisitor: ClassVisitor?) : ClassVisitor(Opcodes.ASM8, classVisitor) {
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

class LoggerFinder(
    classVisitor: ClassVisitor?,
    private val fields: MutableList<LoggerField>
) : BaseClassVisitor(classVisitor) {

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor? {
        super.visitField(access, name, descriptor, signature, value)

        val className = currentClass ?: return null
        if (descriptor != "Lorg/apache/logging/log4j/Logger;") {
            return null
        }
        fields += LoggerField(className, name)
        return null
    }
}

data class LoggerField(val className: String, val fieldName: String)

class ParamIndexInspector(
    classVisitor: ClassVisitor?,
    private val methods: MutableList<MethodData>
) : BaseClassVisitor(classVisitor) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        super.visitMethod(access, name, descriptor, signature, exceptions)

        val className = currentClass ?: return null

        val isStatic = access and Opcodes.ACC_STATIC != 0
        var currentIndex = if (isStatic) 0 else 1

        val types = Type.getArgumentTypes(descriptor)
        if (types.isEmpty()) {
            return null
        }

        val params = ArrayList<ParamTarget>(types.size)
        val data = MethodData(className, name, descriptor, params)
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

        return null
    }
}

data class MethodData(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val params: List<ParamTarget>
)

data class ParamTarget(val binaryIndex: Int, val sourceIndex: Int)
