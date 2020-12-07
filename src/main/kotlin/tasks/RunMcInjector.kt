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
import java.io.ByteArrayInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.inject.Inject
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.LoggingManager
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

abstract class RunMcInjector : BaseTask() {

//    @get:InputFile
//    abstract val configFile: RegularFileProperty
//    @get:InputFile
//    abstract val executable: RegularFileProperty

//    @get:InputFile
//    abstract val exceptions: RegularFileProperty
//    @get:InputFile
//    abstract val access: RegularFileProperty
//    @get:InputFile
//    abstract val constructors: RegularFileProperty

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty
//    @get:Internal
//    abstract val logFile: RegularFileProperty

    @get:Inject
    abstract val loggingManger: LoggingManager

    override fun init() {
        outputJar.convention(defaultOutput())
//        logFile.convention(defaultOutput("log"))
    }

    @TaskAction
    fun run() {
        loggingManger.captureStandardOutput(LogLevel.QUIET)
        loggingManger.captureStandardError(LogLevel.QUIET)

        val classNode = ClassNode(Opcodes.ASM6)
        var visitor: ClassVisitor
        visitor = ParameterAnnotationFixer(classNode, null)
//        visitor = InnerClassInitAdder(visitor)
//        visitor = ClassInitAdder(visitor)

        JarOutputStream(outputJar.file.outputStream()).use { out ->
            archives.zipTree(inputJar.file).matching {
                include("/*.class")
                include("/net/minecraft/**/*.class")
            }.visit(object : FileVisitor {
                override fun visitDir(dirDetails: FileVisitDetails) {}
                override fun visitFile(fileDetails: FileVisitDetails) {
                    val classData = fileDetails.file.readBytes()

                    out.putNextEntry(ZipEntry(fileDetails.path))
                    try {
                        val reader = ClassReader(classData)
                        reader.accept(visitor, 0)

                        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                        classNode.accept(writer)

                        ByteArrayInputStream(writer.toByteArray()).copyTo(out)
                    } finally {
                        out.closeEntry()
                    }
                }
            })
        }


//        val config = gson.fromJson<McpConfig>(configFile)
//
//        val argList = config.functions.mcinject.args.map {
//            when (it) {
//                "{input}" -> inputJar.file.absolutePath
//                "{output}" -> outputJar.file.absolutePath
//                "{log}" -> logFile.file.absolutePath
//                "{exceptions}" -> exceptions.file.absolutePath
//                "{access}" -> access.file.absolutePath
//                "{constructors}" -> constructors.file.absolutePath
//                else -> it
//            }
//        }
//
//        val jvmArgs = config.functions.mcinject.jvmargs ?: listOf()

//        runJar(
//            executable,
//            layout.cache,
//            logFile = null,
//            args = *arrayOf(
//                "--in", inputJar.file.absolutePath,
//                "--out", outputJar.file.absolutePath,
//                "--log", logFile.file.absolutePath,
//                "--level=INFO",
//                "--lvt=STRIP"
//            )
//        )
    }
}
