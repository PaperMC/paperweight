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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode

@CacheableTask
abstract class InspectVanillaJar : BaseTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcLibraries: RegularFileProperty

    @get:OutputFile
    abstract val loggerFile: RegularFileProperty

    @get:OutputFile
    abstract val syntheticMethods: RegularFileProperty

    @get:OutputFile
    abstract val serverLibraries: RegularFileProperty

    override fun init() {
        loggerFile.convention(defaultOutput("$name-loggerFields", "txt"))
        syntheticMethods.convention(defaultOutput("$name-syntheticMethods", "txt"))
    }

    @TaskAction
    fun run() {
        val loggers = mutableListOf<LoggerFields.Data>()
        val synthMethods = mutableListOf<SyntheticMethods.Data>()

        var visitor: ClassVisitor
        visitor = LoggerFields.Visitor(null, loggers)
        visitor = SyntheticMethods.Visitor(visitor, synthMethods)

        inputJar.path.openZip().use { inJar ->
            val rootMatcher = inJar.getPathMatcher("glob:/*.class")
            val nmsMatcher = inJar.getPathMatcher("glob:/net/minecraft/**/*.class")

            inJar.walk().use { stream ->
                stream.filter { p -> !p.isDirectory() }
                    .filter { p -> rootMatcher.matches(p) || nmsMatcher.matches(p) }
                    .map { p -> p.readBytes() }
                    .forEach { data ->
                        ClassReader(data).accept(visitor, 0)
                    }
            }
        }

        val serverLibs = checkLibraries()

        loggerFile.path.bufferedWriter(Charsets.UTF_8).use { writer ->
            loggers.sort()
            for (loggerField in loggers) {
                writer.append(loggerField.className)
                writer.append(' ')
                writer.append(loggerField.fieldName)
                writer.newLine()
            }
        }

        syntheticMethods.path.bufferedWriter(Charsets.UTF_8).use { writer ->
            synthMethods.sort()
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

        serverLibraries.path.bufferedWriter(Charsets.UTF_8).use { writer ->
            serverLibs.map { it.toString() }.sorted().forEach { artifact ->
                writer.appendLine(artifact)
            }
        }
    }

    private fun checkLibraries(): Set<MavenArtifact> {
        val mcLibs = mcLibraries.path.useLines { lines ->
            lines.map { MavenArtifact.parse(it) }
                .map { it.file to it }
                .toMap()
        }

        val serverLibs = mutableSetOf<MavenArtifact>()

        val libs = libraries.files.asSequence()
            .map { f -> f.toPath() }
            .filter { p -> p.isLibraryJar }
            .toList()

        inputJar.path.openZip().use { jar ->
            for (libFile in libs) {
                val artifact = mcLibs[libFile.name] ?: continue

                libFile.openZip().use lib@{ libFs ->
                    val containsClass = libFs.walk().use { stream ->
                        stream.filter { p -> p.name.endsWith(".class") }
                            .anyMatch { p -> jar.getPath(p.absolutePathString()).exists() }
                    }

                    if (containsClass) {
                        serverLibs += artifact
                        return@lib
                    }
                }
            }
        }

        return serverLibs
    }
}

abstract class BaseClassVisitor(classVisitor: ClassVisitor?) : ClassVisitor(Opcodes.ASM9, classVisitor), AsmUtil {
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
object LoggerFields {
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

            if (Opcodes.ACC_STATIC !in access || Opcodes.ACC_FINAL !in access) {
                return ret
            }
            if (descriptor != "Lorg/apache/logging/log4j/Logger;") {
                return ret
            }
            fields += Data(className, name)
            return ret
        }
    }

    data class Data(val className: String, val fieldName: String) : Comparable<Data> {
        override fun compareTo(other: Data) = compareValuesBy(
            this,
            other,
            { it.className },
            { it.fieldName }
        )
    }
}

/*
 * SpecialSource2 automatically handles certain synthetic method renames, which leads to methods which don't match any
 * existing mapping. We need to make a note of all of the synthetic methods which match SpecialSource2's checks so we
 * can handle it in our generated mappings.
 */
object SyntheticMethods {
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

        override fun visitEnd() {
            val (baseName, baseDesc) = SyntheticUtil.findBaseMethod(this, className)

            if (baseName != name || baseDesc != desc) {
                // Add this method as a synthetic for baseName
                methods += Data(className, baseDesc, name, baseName)
            }
        }
    }

    data class Data(
        val className: String,
        val desc: String,
        val synthName: String,
        val baseName: String
    ) : Comparable<Data> {
        override fun compareTo(other: Data) = compareValuesBy(
            this,
            other,
            { it.className },
            { it.desc },
            { it.synthName },
            { it.baseName }
        )
    }
}
