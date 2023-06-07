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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkQueue
import org.objectweb.asm.tree.ClassNode

@CacheableTask
abstract class ScanJarForOldGeneratedCode : ScanJar() {
    companion object {
        val logger: Logger = Logging.getLogger(ScanJarForOldGeneratedCode::class.java)
    }

    @get:Input
    abstract val mcVersion: Property<String>

    @get:Input
    abstract val annotation: Property<String>

    override fun queue(queue: WorkQueue) {
        queue.submit(ScanJarForOldGeneratedCodeAction::class) {
            jarToScan.set(this@ScanJarForOldGeneratedCode.jarToScan)
            classpath.from(this@ScanJarForOldGeneratedCode.classpath)
            log.set(this@ScanJarForOldGeneratedCode.log)
            mcVersion.set(this@ScanJarForOldGeneratedCode.mcVersion)
            annotation.set(this@ScanJarForOldGeneratedCode.annotation)
        }
    }

    abstract class ScanJarForOldGeneratedCodeAction : ScanJarAction<ScanJarForOldGeneratedCodeAction.Parameters>() {
        interface Parameters : BaseParameters {
            val mcVersion: Property<String>
            val annotation: Property<String>
        }

        override fun handleClass(classNode: ClassNode, classNodeCache: ClassNodeCache) {
            val annotations = (classNode.visibleAnnotations ?: emptyList()) + (classNode.invisibleAnnotations ?: emptyList())

            val generatedAnnotation = annotations
                .find { it.desc == parameters.annotation.get() }
                ?.values
                ?.chunked(2)
                ?.find { it[0] == "value" } ?: return

            val generatedVersion = generatedAnnotation[1].toString()
            val mcVersion = parameters.mcVersion.get()

            if (generatedVersion != mcVersion) {
                val msg = errorMsg(classNode, generatedVersion, mcVersion)
                log += msg
                logger.error(msg)
            }
        }

        private fun errorMsg(classNode: ClassNode, generatedVersion: String, mcVersion: String): String {
            return "Class ${classNode.name} is marked as being generated in version $generatedVersion when the set version is $mcVersion"
        }
    }
}
