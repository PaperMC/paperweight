/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.AsmUtil
import io.papermc.paperweight.util.ClassNodeCache
import io.papermc.paperweight.util.MavenArtifact
import io.papermc.paperweight.util.MethodRef
import io.papermc.paperweight.util.SyntheticUtil
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.isLibraryJar
import io.papermc.paperweight.util.writeOverrides
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import org.jgrapht.nio.Attribute
import org.jgrapht.nio.DefaultAttribute
import org.jgrapht.nio.dot.DOTExporter
import org.jgrapht.nio.dot.DOTImporter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

abstract class InspectVanillaJar : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty
    @get:InputDirectory
    abstract val librariesDir: DirectoryProperty
    @get:InputFile
    abstract val mcLibraries: RegularFileProperty

    @get:OutputFile
    abstract val loggerFile: RegularFileProperty
    @get:OutputFile
    abstract val paramIndexes: RegularFileProperty
    @get:OutputFile
    abstract val syntheticMethods: RegularFileProperty
    @get:OutputFile
    abstract val methodOverrides: RegularFileProperty
    @get:OutputFile
    abstract val serverLibraries: RegularFileProperty

    override fun init() {
        loggerFile.convention(defaultOutput("$name-loggerFields", "txt"))
        paramIndexes.convention(defaultOutput("$name-paramIndexes", "txt"))
        syntheticMethods.convention(defaultOutput("$name-syntheticMethods", "txt"))
        methodOverrides.convention(defaultOutput("$name-methodOverrides", "json"))
    }

    @TaskAction
    fun run() {
        val loggers = mutableListOf<LoggerFields.Data>()
        val params = mutableListOf<ParamIndexes.Data>()
        val synthMethods = mutableListOf<SyntheticMethods.Data>()
        val overrides = SimpleDirectedGraph<MethodRef, DefaultEdge>(DefaultEdge::class.java)

        JarFile(inputJar.file).use { jarFile ->
            val classNodeCache = ClassNodeCache(jarFile)

            for (entry in jarFile.entries()) {
                if (!entry.name.endsWith(".class")) {
                    continue
                }
                if (entry.name.count { it == '/' } > 0 && !entry.name.startsWith("net/minecraft/")) {
                    continue
                }

                val node = classNodeCache.findClass(entry.name) ?: error("No ClassNode found for known entry")

                LoggerFields.Visitor.visit(node, loggers)
                ParamIndexes.Visitor.visit(node, params)
                SyntheticMethods.Visitor.visit(node, synthMethods)
                MethodOverrides.Visitor.visit(node, classNodeCache, overrides)
            }
        }

        val serverLibs = checkLibraries()

        loggers.sort()
        loggerFile.file.bufferedWriter().use { writer ->
            for (loggerField in loggers) {
                writer.append(loggerField.className)
                writer.append(' ')
                writer.append(loggerField.fieldName)
                writer.newLine()
            }
        }

        params.sort()
        paramIndexes.file.bufferedWriter().use { writer ->
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

        synthMethods.sort()
        syntheticMethods.file.bufferedWriter().use { writer ->
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

        writeOverrides(overrides, methodOverrides)

        val libs = serverLibs.map { it.toString() }.sorted()
        serverLibraries.file.bufferedWriter().use { writer ->
            libs.forEach { artifact ->
                writer.appendln(artifact)
            }
        }
    }

    private fun checkLibraries(): Set<MavenArtifact> {
        val mcLibs = mcLibraries.file.useLines { lines ->
            lines.map { MavenArtifact.parse(it) }
                .map { it.file to it }
                .toMap()
        }

        val serverLibs = mutableSetOf<MavenArtifact>()

        val libs = librariesDir.file.listFiles()?.filter { it.isLibraryJar } ?: emptyList()
        ZipFile(inputJar.file).use { jar ->
            for (libFile in libs) {
                val artifact = mcLibs[libFile.name] ?: continue

                ZipFile(libFile).use { lib ->
                    for (entry in lib.entries()) {
                        if (!entry.name.endsWith(".class")) {
                            continue
                        }
                        if (jar.getEntry(entry.name) != null) {
                            serverLibs += artifact
                        }
                        break
                    }
                }
            }
        }

        return serverLibs
    }
}

/*
 * SpecialSource2 automatically maps all Logger fields to the name LOGGER, without needing mappings defined, so we need
 * to make a note of all of those fields
 */
object LoggerFields {
    object Visitor : AsmUtil {

        fun visit(node: ClassNode, fields: MutableList<Data>) {
            for (field in node.fields) {
                if (Opcodes.ACC_STATIC !in field.access || Opcodes.ACC_FINAL !in field.access) {
                    continue
                }
                if (field.desc == "Lorg/apache/logging/log4j/Logger;") {
                    fields += Data(node.name, field.name)
                }
            }
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
 * Source-remapping uses 0-based param indexes, but the param indexes we have are LVT-based. We have to look at the
 * actual bytecode to translate the LVT-based indexes back to 0-based param indexes.
 */
object ParamIndexes {
    object Visitor : AsmUtil {

        fun visit(node: ClassNode, methods: MutableList<Data>) {
            for (method in node.methods) {
                val isStatic = Opcodes.ACC_STATIC in method.access
                var currentIndex = if (isStatic) 0 else 1

                val types = Type.getArgumentTypes(method.desc)
                if (types.isEmpty()) {
                    continue
                }

                val params = ArrayList<ParamTarget>(types.size)
                val data = Data(node.name, method.name, method.desc, params)
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
            }
        }
    }

    data class Data(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
        val params: List<ParamTarget>
    ) : Comparable<Data> {
        override fun compareTo(other: Data) = compareValuesBy(
            this,
            other,
            { it.className },
            { it.methodName },
            { it.methodDescriptor }
        )
    }

    data class ParamTarget(val binaryIndex: Int, val sourceIndex: Int) : Comparable<ParamTarget> {
        override fun compareTo(other: ParamTarget) = compareValuesBy(this, other) { it.binaryIndex }
    }
}

/*
 * SpecialSource2 automatically handles certain synthetic method renames, which leads to methods which don't match any
 * existing mapping. We need to make a note of all of the synthetic methods which match SpecialSource2's checks so we
 * can handle it in our generated mappings.
 */
object SyntheticMethods {
    object Visitor : AsmUtil {

        fun visit(node: ClassNode, methods: MutableList<Data>) {
            for (method in node.methods) {
                if (Opcodes.ACC_SYNTHETIC !in method.access || Opcodes.ACC_BRIDGE in method.access || method.name.contains('$')) {
                    continue
                }

                val (baseName, baseDesc) = SyntheticUtil.findBaseMethod(method, node.name)

                if (baseName != method.name || baseDesc != method.desc) {
                    // Add this method as a synthetic for baseName
                    methods += Data(node.name, baseDesc, method.name, baseName)
                }
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

/*
 * There's no direct marker in bytecode to know if a
 */
object MethodOverrides {
    object Visitor : AsmUtil {

        fun visit(
            node: ClassNode,
            classNodeCache: ClassNodeCache,
            methodOverrides: SimpleDirectedGraph<MethodRef, DefaultEdge>
        ) {
            val superMethods = collectSuperMethods(node, classNodeCache)

            val disqualifiedMethods = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
            for (method in node.methods) {
                if (method.access in disqualifiedMethods) {
                    continue
                }

                if (method.name == "<init>" || method.name == "<clinit>") {
                    continue
                }
                val (name, desc) = SyntheticUtil.findBaseMethod(method, node.name)

                superMethods[method.name + method.desc]?.let { className ->
                    val targetMethod = node.methods.firstOrNull { it.name == name && it.desc == desc } ?: method

                    val methodRef = MethodRef(node.name, method.name, method.desc)
                    val targetMethodRef = MethodRef(className, targetMethod.name, targetMethod.desc)
                    methodOverrides.addVertex(methodRef)
                    methodOverrides.addVertex(targetMethodRef)
                    methodOverrides.addEdge(methodRef, targetMethodRef)
                }
            }
        }

        private fun collectSuperMethods(node: ClassNode, classNodeCache: ClassNodeCache): Map<String, String> {
            fun collectSuperMethods(node: ClassNode, superMethods: HashMap<String, String>) {
                val supers = listOfNotNull(node.superName, *node.interfaces.toTypedArray())
                if (supers.isEmpty()) {
                    return
                }

                val disqualifiedMethods = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
                val superNodes = supers.mapNotNull { classNodeCache.findClass(it) }
                for (superNode in superNodes) {
                    superNode.methods.asSequence()
                        .filter { method -> method.access !in disqualifiedMethods }
                        .filter { method -> method.name != "<init>" && method.name != "<clinit>" }
                        .map { method -> method.name + method.desc to superNode.name }
                        .toMap(superMethods)
                }

                for (superNode in superNodes) {
                    collectSuperMethods(superNode, superMethods)
                }
            }

            val result = hashMapOf<String, String>()
            collectSuperMethods(node, result)
            return result
        }
    }
}
