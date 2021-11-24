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
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

fun fixJar(
    workerExecutor: WorkerExecutor,
    jvmArgs: List<String> = arrayListOf("-Xmx512m"),
    launcher: JavaLauncher,
    vanillaJarPath: Path,
    inputJarPath: Path,
    outputJarPath: Path
): WorkQueue {
    ensureParentExists(outputJarPath)
    ensureDeleted(outputJarPath)

    val queue = workerExecutor.processIsolation {
        forkOptions.jvmArgs(jvmArgs)
        forkOptions.executable(launcher.executablePath.path.absolutePathString())
    }

    queue.submit(FixJarTask.FixJarAction::class) {
        inputJar.set(inputJarPath)
        vanillaJar.set(vanillaJarPath)
        outputJar.set(outputJarPath)
    }

    return queue
}

@CacheableTask
abstract class FixJarTask : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:Classpath
    abstract val vanillaJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmArgs.convention(listOf("-Xmx512m"))
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        fixJar(
            workerExecutor = workerExecutor,
            jvmArgs = jvmArgs.get(),
            launcher = launcher.get(),
            vanillaJarPath = vanillaJar.path,
            inputJarPath = inputJar.path,
            outputJarPath = outputJar.path
        )
    }

    interface FixJarParams : WorkParameters {
        val inputJar: RegularFileProperty
        val vanillaJar: RegularFileProperty
        val outputJar: RegularFileProperty
    }

    abstract class FixJarAction : WorkAction<FixJarParams> {

        override fun execute() {
            parameters.vanillaJar.path.openZip().use { vanillaJar ->
                parameters.outputJar.path.writeZip().use { out ->
                    parameters.inputJar.path.openZip().use { jarFile ->
                        FixJar.processJars(jarFile, vanillaJar, out, FixJarClassProcessor)
                    }
                }
            }
        }

        private object FixJarClassProcessor : FixJar.ClassProcessor {
            override fun processClass(node: ClassNode, classNodeCache: ClassNodeCache) {
                SpongeRecordFixer.fix(node, classNodeCache, true, true)
                ParameterAnnotationFixer(node).visitNode()
                OverrideAnnotationAdder(node, classNodeCache).visitNode()
            }
        }
    }
}

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
            println("Cannot process method local class: ${innerNode.name}")
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
