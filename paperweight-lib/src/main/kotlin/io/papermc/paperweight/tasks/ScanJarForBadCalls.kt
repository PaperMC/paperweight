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
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkQueue
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

@CacheableTask
abstract class ScanJarForBadCalls : ScanJar() {
    companion object {
        val logger: Logger = Logging.getLogger(ScanJarForBadCalls::class.java)
    }

    @get:Input
    abstract val badAnnotations: SetProperty<String>

    override fun queue(queue: WorkQueue) {
        queue.submit(ScanJarForBadCallsAction::class) {
            jarToScan.set(this@ScanJarForBadCalls.jarToScan)
            classpath.from(this@ScanJarForBadCalls.classpath)
            log.set(this@ScanJarForBadCalls.log)
            badAnnotations.set(this@ScanJarForBadCalls.badAnnotations)
        }
    }

    abstract class ScanJarForBadCallsAction : ScanJarAction<ScanJarForBadCallsAction.Parameters>(), AsmUtil {
        interface Parameters : BaseParameters {
            val badAnnotations: SetProperty<String>
        }

        override fun handleClass(classNode: ClassNode, classNodeCache: ClassNodeCache) {
            for (method in classNode.methods) {
                method.instructions.forEach { handleInstruction(classNode, method, it, classNodeCache) }
            }
        }

        private fun handleInstruction(classNode: ClassNode, method: MethodNode, absIsnNode: AbstractInsnNode, classNodeCache: ClassNodeCache) {
            when (absIsnNode) {
                is InvokeDynamicInsnNode -> handleInvokeDynamic(classNode, method, absIsnNode, classNodeCache)
                is MethodInsnNode -> handleMethodInvocation(classNode, method, absIsnNode, classNodeCache)
            }
        }

        private fun handleInvokeDynamic(
            classNode: ClassNode,
            method: MethodNode,
            invokeDynamicInsnNode: InvokeDynamicInsnNode,
            classNodeCache: ClassNodeCache
        ) {
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
