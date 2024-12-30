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

package io.papermc.paperweight.userdev.internal.util

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

fun fixJar(
    workerExecutor: WorkerExecutor,
    jvmArgs: List<String> = arrayListOf("-Xmx512m"),
    launcher: JavaLauncher,
    vanillaJarPath: Path,
    inputJarPath: Path,
    outputJarPath: Path,
    useLegacyParameterAnnotationFixer: Boolean = false,
): WorkQueue {
    ensureParentExists(outputJarPath)
    ensureDeleted(outputJarPath)

    val queue = workerExecutor.processIsolation {
        forkOptions.jvmArgs(jvmArgs)
        forkOptions.executable(launcher.executablePath.path.absolutePathString())
    }

    queue.submit(FixJarAction::class) {
        inputJar.set(inputJarPath)
        vanillaJar.set(vanillaJarPath)
        outputJar.set(outputJarPath)
        useLegacyParamAnnotationFixer.set(useLegacyParameterAnnotationFixer)
    }

    return queue
}

abstract class FixJarAction : WorkAction<FixJarAction.Params> {
    interface Params : WorkParameters {
        val inputJar: RegularFileProperty
        val vanillaJar: RegularFileProperty
        val outputJar: RegularFileProperty
        val useLegacyParamAnnotationFixer: Property<Boolean>
    }

    override fun execute() {
        parameters.vanillaJar.path.openZip().use { vanillaJar ->
            parameters.outputJar.path.writeZip().use { out ->
                parameters.inputJar.path.openZip().use { jarFile ->
                    JarProcessing.processJar(
                        jarFile,
                        vanillaJar,
                        out,
                        FixJarClassProcessor(parameters.useLegacyParamAnnotationFixer.get())
                    )
                }
            }
        }
    }

    private class FixJarClassProcessor(private val legacy: Boolean) : JarProcessing.ClassProcessor.NodeBased, AsmUtil {
        override fun processClass(node: ClassNode, classNodeCache: ClassNodeCache) {
            if (legacy) {
                ParameterAnnotationFixer(node).visitNode()
            }

            OverrideAnnotationAdder(node, classNodeCache).visitNode()

            if (Opcodes.ACC_RECORD in node.access) {
                RecordFieldAccessFixer(node).visitNode()
            }
        }
    }
}

// Fix proguard changing access of record fields
class RecordFieldAccessFixer(private val node: ClassNode) : AsmUtil {
    fun visitNode() {
        for (field in node.fields) {
            if (Opcodes.ACC_STATIC !in field.access && Opcodes.ACC_FINAL in field.access && Opcodes.ACC_PRIVATE !in field.access) {
                field.access = field.access and AsmUtil.RESET_ACCESS or Opcodes.ACC_PRIVATE
            }
        }
    }
}

class OverrideAnnotationAdder(private val node: ClassNode, private val classNodeCache: ClassNodeCache) : AsmUtil {

    fun visitNode() {
        val superMethods = collectSuperMethods(node)

        val disqualifiedMethods = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
        for (method in node.methods) {
            if (method.access in disqualifiedMethods) {
                continue
            }

            if (method.name == "<init>" || method.name == "<clinit>") {
                continue
            }
            val (name, desc) = SyntheticUtil.findBaseMethod(method, node.name)

            if (method.name + method.desc in superMethods) {
                val targetMethod = node.methods.firstOrNull { it.name == name && it.desc == desc } ?: method

                if (targetMethod.invisibleAnnotations == null) {
                    targetMethod.invisibleAnnotations = arrayListOf()
                }
                val annoClass = "Ljava/lang/Override;"
                if (targetMethod.invisibleAnnotations.none { it.desc == annoClass }) {
                    targetMethod.invisibleAnnotations.add(AnnotationNode(annoClass))
                }
            }
        }
    }

    private fun collectSuperMethods(node: ClassNode): Set<String> {
        fun collectSuperMethods(node: ClassNode, superMethods: HashSet<String>) {
            val supers = listOfNotNull(node.superName, *node.interfaces.toTypedArray())
            if (supers.isEmpty()) {
                return
            }

            val disqualifiedMethods = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
            val superNodes = supers.mapNotNull { classNodeCache.findClass(it) }
            superNodes.asSequence()
                .flatMap { classNode -> classNode.methods.asSequence() }
                .filter { method -> method.access !in disqualifiedMethods }
                .filter { method -> method.name != "<init>" && method.name != "<clinit>" }
                .map { method -> method.name + method.desc }
                .toCollection(superMethods)

            for (superNode in superNodes) {
                collectSuperMethods(superNode, superMethods)
            }
        }

        val result = hashSetOf<String>()
        collectSuperMethods(node, result)
        return result
    }
}
