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
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

@Suppress("LeakingThis")
abstract class RelocateClassNameConstants : BaseTask() {
    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Nested
    @get:Optional
    abstract val relocations: ListProperty<RelocationInput>

    @get:Input
    @get:Optional
    abstract val processOnly: ListProperty<String>

    fun relocate(fromPackage: String, toPackage: String, op: Action<RelocationInput>) {
        relocations.add(
            objects.newInstance<RelocationInput>().apply {
                this.fromPackage.set(fromPackage)
                this.toPackage.set(toPackage)
                op.execute(this)
            }
        )
    }

    init {
        outputJar.convention(defaultOutput())
        processOnly.convention(
            listOf(
                "org/bukkit/craftbukkit/**/*.class",
                "org/bukkit/craftbukkit/*.class"
            )
        )
    }

    @TaskAction
    fun run() {
        outputJar.path.deleteForcefully()
        outputJar.path.parent.createDirectories()
        val relocations = relocations.get().map {
            RelocationWrapper(Relocation(null, it.fromPackage.get(), it.toPackage.get(), emptyList()))
        }
        outputJar.path.writeZip().use { outputFs ->
            inputJar.path.openZip().use { inputFs ->
                val includes = processOnly.getOrElse(emptyList()).map {
                    inputFs.getPathMatcher("glob:${if (it.startsWith('/')) it else "/$it"}")
                }
                JarProcessing.processJar(
                    inputFs,
                    outputFs,
                    object : JarProcessing.ClassProcessor.VisitorBased {
                        override fun shouldProcess(file: Path): Boolean =
                            includes.isEmpty() || includes.any { it.matches(file) }

                        override fun processClass(node: ClassNode, parent: ClassVisitor, classNodeCache: ClassNodeCache): ClassVisitor =
                            ConstantRelocatingClassVisitor(parent, relocations)
                    }
                )
            }
        }
    }

    private class ConstantRelocatingClassVisitor(
        parent: ClassVisitor,
        private val relocations: List<RelocationWrapper>
    ) : ClassVisitor(Opcodes.ASM9, parent) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                override fun visitLdcInsn(value: Any?) {
                    if (value is String) {
                        var v: String = value
                        for (relocation in relocations) {
                            if (v.startsWith(relocation.fromDot)) {
                                v = v.replace(relocation.fromDot, relocation.toDot)
                            } else if (v.startsWith(relocation.fromSlash)) {
                                v = v.replace(relocation.fromSlash, relocation.toSlash)
                            }
                        }
                        super.visitLdcInsn(v)
                    } else {
                        super.visitLdcInsn(value)
                    }
                }
            }
        }
    }

    abstract class RelocationInput {
        @get:Input
        abstract val fromPackage: Property<String>

        @get:Input
        abstract val toPackage: Property<String>
    }
}
