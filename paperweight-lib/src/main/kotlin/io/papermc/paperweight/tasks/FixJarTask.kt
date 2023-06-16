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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.ParameterAnnotationFixer
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
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
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

fun fixJar(
    workerExecutor: WorkerExecutor,
    jvmArgs: List<String> = arrayListOf("-Xmx512m"),
    launcher: JavaLauncher,
    vanillaJarPath: Path,
    inputJarPath: Path,
    outputJarPath: Path,
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
                        JarProcessing.processJar(
                            jarFile,
                            vanillaJar,
                            out,
                            FixJarClassProcessor()
                        )
                    }
                }
            }
        }

        private class FixJarClassProcessor : JarProcessing.ClassProcessor.NodeBased, AsmUtil {
            override fun processClass(node: ClassNode, classNodeCache: ClassNodeCache) {
                OverrideAnnotationAdder(node, classNodeCache).visitNode()
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
