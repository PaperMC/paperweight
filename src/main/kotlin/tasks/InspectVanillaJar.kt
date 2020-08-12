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
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

open class InspectVanillaJar : DefaultTask() {

    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val outputFile: RegularFileProperty = defaultOutput("txt")

    @TaskAction
    fun run() {
        val outputList = mutableListOf<LoggerField>()

        val jarFile = inputJar.file
        project.zipTree(jarFile).matching {
            include("/*.class")
            include("/net/minecraft/**/*.class")
        }.forEach { file ->
            if (file.isDirectory) {
                return@forEach
            }
            val classData = file.readBytes()

            val reader = ClassReader(classData)
            reader.accept(LoggerFinder(outputList), 0)

        }

        outputFile.file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (loggerField in outputList) {
                writer.append(loggerField.className)
                writer.append(' ')
                writer.append(loggerField.fieldName)
                writer.newLine()
            }
        }
    }
}

class LoggerFinder(private val fields: MutableList<LoggerField>) : ClassVisitor(Opcodes.ASM8) {

    private var currentClass: String? = null

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.currentClass = name
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor? {
        val className = currentClass ?: return null
        if (descriptor != "Lorg/apache/logging/log4j/Logger;") {
            return null
        }
        fields += LoggerField(className, name)
        return null
    }
}

data class LoggerField(val className: String, val fieldName: String)
