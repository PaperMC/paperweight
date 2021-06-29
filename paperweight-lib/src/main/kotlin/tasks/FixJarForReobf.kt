/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.set
import io.papermc.paperweight.util.walk
import io.papermc.paperweight.util.writeZip
import java.nio.file.FileSystem
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodNode

abstract class FixJarForReobf : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val packagesToProcess: ListProperty<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        outputJar.convention(defaultOutput())
        jvmargs.convention(listOf("-Xmx2G"))
    }

    @TaskAction
    fun run() {
        val pack = packagesToProcess.orNull
        if (pack == null) {
            inputJar.path.copyTo(outputJar.path)
            return
        }

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
        }

        queue.submit(FixJarForReobfWorker::class) {
            inputJar.set(this@FixJarForReobf.inputJar.path)
            packagesToProcess.set(pack)
            outputJar.set(this@FixJarForReobf.outputJar.path)
        }
    }

    interface FixJarForReobfParams : WorkParameters {
        val inputJar: RegularFileProperty
        val packagesToProcess: ListProperty<String>
        val outputJar: RegularFileProperty
    }

    abstract class FixJarForReobfWorker : WorkAction<FixJarForReobfParams> {

        override fun execute() {
            val packages = normalize(parameters.packagesToProcess.get())

            val output = parameters.outputJar.path
            output.parent.createDirectories()
            output.deleteForcefully()

            output.writeZip().use { out ->
                parameters.inputJar.path.openZip().use { jarFile ->
                    processJars(jarFile, out, packages)
                }
            }
        }

        private fun processJars(jarFile: FileSystem, output: FileSystem, packages: List<String>) {
            val classNodeCache = ClassNodeCache(jarFile)

            jarFile.walk().use { stream ->
                stream.forEach { file ->
                    processFile(file, output, packages, classNodeCache)
                }
            }
        }

        private fun processFile(file: Path, output: FileSystem, packages: List<String>, classNodeCache: ClassNodeCache) {
            val outFile = output.getPath(file.toString())

            if (file.isDirectory()) {
                outFile.createDirectories()
                return
            }

            if (!file.name.endsWith(".class")) {
                file.copyTo(outFile)
                return
            }

            if (packages.none { file.toString().startsWith(it) }) {
                file.copyTo(outFile)
                return
            }

            processClassFile(file, outFile, classNodeCache)
        }

        private fun processClassFile(file: Path, outFile: Path, classNodeCache: ClassNodeCache) {
            val node = classNodeCache.findClass(file.toString()) ?: error("No ClassNode found for known entry: ${file.name}")

            FieldAccessNormalizer(node, classNodeCache).visitNode()

            val writer = ClassWriter(0)
            node.accept(writer)

            outFile.writeBytes(writer.toByteArray())
        }

        private fun normalize(input: List<String>): List<String> {
            return input.map { name ->
                '/' + name.removePrefix("/").replace('.', '/')
            }
        }
    }
}

/*
 * This resolves issues caused by reobf prior to the reobf process. After reobf this is impossible to do - the field access become ambiguous (which is
 * what this fixes).
 *
 * What exactly this is fixing requires some knowledge around how the JVM handles field accesses in the first place - Mumfrey described this process
 * in detail with some great diagrams several years ago, you can read that here: https://github.com/MinecraftForge/MinecraftForge/pull/3055
 *
 * The goal of this class is to check all field access instructions (not field declarations) and follow the JVM's rules for field binding in order
 * to determine the _intended_ owning class of a field access. Prior to reobf all of this works exactly as expected when looking at Java source code,
 * but after reobf there are many cases that look like this:
 *
 *     field `a` declared in class `Foo`
 *     field `a` declared in class `Bar` which extends `Foo`
 *
 * In the deobfuscated code these fields would have different names, so they won't overlap and the JVM will output field access instructions described
 * in the link above. Reobf generally only changes the field's name and type (and the name of the owner class), but it doesn't actually fix the issue
 * where field accesses which used to be unambiguous are now ambiguous.
 *
 * So with that in mind, this class will look at field access instructions and match the actual field the instruction is trying to access (even if
 * it's not directly declared in the owner class) and change the owner accordingly. This will keep field accesses unambiguous even after reobf with
 * conflicting field names.
 */
class FieldAccessNormalizer(private val node: ClassNode, private val classNodeCache: ClassNodeCache) : AsmUtil {

    fun visitNode() {
        for (method in node.methods) {
            visitMethod(method)
        }
    }

    private fun visitMethod(method: MethodNode) {
        for (instruction in method.instructions) {
            val fieldInst = instruction as? FieldInsnNode ?: continue
            visitFieldInst(fieldInst)
        }
    }

    private fun visitFieldInst(instruction: FieldInsnNode) {
        val ownerNode = findTargetFieldDeclaration(instruction) ?: return
        instruction.owner = ownerNode.name
    }

    private fun findTargetFieldDeclaration(instruction: FieldInsnNode): ClassNode? {
        val fieldName = instruction.name

        var className: String? = instruction.owner
        while (className != null) {
            val currentNode = classNodeCache.findClass(className) ?: return null

            val fieldNode = currentNode.fields.firstOrNull { it.name == fieldName }
            if (fieldNode != null) {
                /*
                 * We need to determine if this field node can actually be accessed by the caller (the original `node`).
                 * For example, consider the following class hierarchy:
                 *
                 *     class Foo
                 *         public field text
                 *     class Bar extends Foo
                 *         private field text
                 *     class Cat extends Bar
                 *
                 * If `Cat` contains a method which accesses `this.text` then by Java's field access rules the field access would bind to `Foo.text`
                 * rather than `Bar.text`, even though `Bar.text` shadows `Foo.text`. This is of course because `Cat` is not able to access `Bar.text`
                 * since it's a private field. Private fields are of course the easier case to handle - we also have to check protected fields if the
                 * original `node` does not extend the field's declaring class, and package private if the classes aren't in the same package.
                 */

                if (Opcodes.ACC_PRIVATE in fieldNode.access) {
                    // This is only legal if the field node owner and the original node match
                    if (currentNode.name == node.name) {
                        return currentNode
                    }
                } else if (Opcodes.ACC_PROTECTED in fieldNode.access) {
                    var walkingNode: ClassNode? = node
                    while (walkingNode != null) {
                        if (walkingNode.name == currentNode.name) {
                            return currentNode
                        }
                        walkingNode = classNodeCache.findClass(walkingNode.superName)
                    }
                } else if (Opcodes.ACC_PUBLIC in fieldNode.access) {
                    return currentNode
                } else {
                    // package private field
                    val currentPackage = currentNode.name.substringBeforeLast('/')
                    val originalPackage = node.name.substringBeforeLast('/')
                    if (currentPackage == originalPackage) {
                        return currentNode
                    }
                }
            }

            className = currentNode.superName
        }

        return null
    }
}
