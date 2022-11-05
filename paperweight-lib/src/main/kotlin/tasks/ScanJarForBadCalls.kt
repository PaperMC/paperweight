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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.AsmUtil
import io.papermc.paperweight.util.ClassNodeCache
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.constants.paperTaskOutput
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.set
import io.papermc.paperweight.util.walk
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeLines
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

@CacheableTask
abstract class ScanJarForBadCalls : JavaLauncherTask() {
    companion object {
        val logger: Logger = Logging.getLogger(ScanJarForBadCalls::class.java)
    }

    @get:Classpath
    abstract val jarToScan: RegularFileProperty

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val log: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val badAnnotations: SetProperty<String>

    override fun init() {
        super.init()

        jvmArgs.convention(listOf("-Xmx512m"))
        log.set(layout.cache.resolve(paperTaskOutput("txt")))
    }

    @TaskAction
    fun run() {
        val launcher = launcher.get()
        val jvmArgs = jvmArgs.get()
        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmArgs)
            forkOptions.executable(launcher.executablePath.path.absolutePathString())
        }

        queue.submit(ScanJarAction::class) {
            jarToScan.set(this@ScanJarForBadCalls.jarToScan)
            classpath.from(this@ScanJarForBadCalls.classpath)
            log.set(this@ScanJarForBadCalls.log)
            badAnnotations.set(this@ScanJarForBadCalls.badAnnotations)
        }
    }

    abstract class ScanJarAction : WorkAction<ScanJarAction.Parameters>, AsmUtil {
        interface Parameters : WorkParameters {
            val jarToScan: RegularFileProperty
            val classpath: ConfigurableFileCollection
            val log: RegularFileProperty
            val badAnnotations: SetProperty<String>
        }

        private val log = mutableListOf<String>()

        override fun execute() {
            parameters.jarToScan.path.openZip().use { scan ->
                var fail: Exception? = null
                val classPathDirs = mutableListOf<Path>()
                val classPathJars = mutableListOf<FileSystem>()
                parameters.classpath.forEach {
                    if (it.isDirectory) {
                        classPathDirs.add(it.toPath())
                        return@forEach
                    }
                    if (!it.isFile || !it.name.endsWith(".jar")) {
                        return@forEach
                    }
                    try {
                        classPathJars += it.toPath().openZip()
                    } catch (ex: Exception) {
                        logger.error("Failed to open zip $it", ex)
                        if (fail == null) {
                            fail = ex
                        } else {
                            fail!!.addSuppressed(ex)
                        }
                    }
                }
                try {
                    if (fail != null) {
                        throw PaperweightException("Failed to read classpath jars", fail)
                    }
                    val classNodeCache = ClassNodeCache.create(scan, classPathJars, classPathDirs)
                    scan(scan, classNodeCache)
                } finally {
                    var err: Exception? = null
                    classPathJars.forEach {
                        try {
                            it.close()
                        } catch (ex: Exception) {
                            logger.error("Failed to close zip $it", ex)
                            if (err == null) {
                                err = ex
                            } else {
                                err!!.addSuppressed(ex)
                            }
                        }
                    }
                    if (err != null) {
                        throw PaperweightException("Failed to close classpath jars", err)
                    }
                }
            }
            if (!Files.exists(parameters.log.path.parent)) {
                Files.createDirectories(parameters.log.path.parent)
            }
            parameters.log.path.writeLines(log)

            if (log.isNotEmpty()) {
                throw PaperweightException("Bad method usages were found, see log file at ${parameters.log.path.toAbsolutePath()}")
            }
        }

        private fun scan(scan: FileSystem, classNodeCache: ClassNodeCache) {
            scan.walk().use { stream ->
                stream.forEach { file ->
                    if (!Files.isRegularFile(file) || !file.fileName.toString().endsWith(".class")) {
                        return@forEach
                    }
                    val classNode = classNodeCache.findClass(file.toString()) ?: return@forEach

                    for (method in classNode.methods) {
                        method.instructions.forEach { handleInstruction(classNode, method, it, classNodeCache) }
                    }
                }
            }
        }

        private fun handleInstruction(classNode: ClassNode, method: MethodNode, absIsnNode: AbstractInsnNode, classNodeCache: ClassNodeCache) {
            when (absIsnNode) {
                is InvokeDynamicInsnNode -> handleInvokeDynamic(classNode, method, absIsnNode, classNodeCache)
                is MethodInsnNode -> handleMethodInvocation(classNode, method, absIsnNode, classNodeCache)
            }
        }

        private fun handleInvokeDynamic(classNode: ClassNode, method: MethodNode, invokeDynamicInsnNode: InvokeDynamicInsnNode, classNodeCache: ClassNodeCache) {
            if (invokeDynamicInsnNode.bsm.owner == "java/lang/invoke/LambdaMetafactory" && invokeDynamicInsnNode.bsmArgs.size > 1) {
                when (val methodHandle = invokeDynamicInsnNode.bsmArgs[1]) {
                    is Handle -> checkMethod(classNode, method, methodHandle.owner, methodHandle.name, methodHandle.desc, classNodeCache)
                }
            }
        }

        private fun handleMethodInvocation(classNode: ClassNode, method: MethodNode, methodIsnNode: MethodInsnNode, classNodeCache: ClassNodeCache) {
            checkMethod(classNode, method, methodIsnNode.owner, methodIsnNode.name, methodIsnNode.desc, classNodeCache)
        }

        private fun checkMethod(classNode: ClassNode, method: MethodNode, owner: String, name: String, desc: String, classNodeCache: ClassNodeCache) {
            val targetOwner = classNodeCache.findClass(owner) ?: return
            val target = targetOwner.methods.find {
                it.name == name && it.desc == desc
            } ?: return

            val annotations = (target.visibleAnnotations ?: emptyList()) + (target.invisibleAnnotations ?: emptyList())
            annotations.find { it.desc in parameters.badAnnotations.get() } ?: return

            val msg = warnMsg(classNode, method, targetOwner, target)
            log += msg
            logger.error(msg)
        }

        private fun warnMsg(classNode: ClassNode, method: MethodNode, targetOwner: ClassNode, target: MethodNode): String {
            val methodDelimiter = if (Opcodes.ACC_STATIC in method.access) '.' else '#'
            val targetMethodDelimiter = if (Opcodes.ACC_STATIC in target.access) '.' else '#'
            return "Method ${classNode.name}$methodDelimiter${method.name}${method.desc} " +
                "includes reference to bad method ${targetOwner.name}$targetMethodDelimiter${target.name}${target.desc}"
        }
    }
}
