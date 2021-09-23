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
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.atlas.Atlas
import org.cadixdev.atlas.AtlasTransformerContext
import org.cadixdev.bombe.analysis.InheritanceProvider
import org.cadixdev.bombe.jar.JarClassEntry
import org.cadixdev.bombe.jar.JarEntryTransformer
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

@CacheableTask
abstract class ApplyAccessTransform : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx1G"))
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        ensureParentExists(outputJar.path)
        ensureDeleted(outputJar.path)

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmargs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        queue.submit(AtlasAction::class) {
            inputJar.set(this@ApplyAccessTransform.inputJar.path)
            atFile.set(this@ApplyAccessTransform.atFile.path)
            outputJar.set(this@ApplyAccessTransform.outputJar.path)
        }
    }

    abstract class AtlasAction : WorkAction<AtlasParameters> {
        override fun execute() {
            val at = AccessTransformFormats.FML.read(parameters.atFile.path)

            Atlas().apply {
                install {
                    AtJarEntryTransformer(it, at)
                }
                run(parameters.inputJar.path, parameters.outputJar.path)
            }
        }
    }

    interface AtlasParameters : WorkParameters {
        val inputJar: RegularFileProperty
        val atFile: RegularFileProperty
        val outputJar: RegularFileProperty
    }
}

class AtJarEntryTransformer(
    private val context: AtlasTransformerContext,
    private val at: AccessTransformSet
) : JarEntryTransformer {
    override fun transform(entry: JarClassEntry): JarClassEntry {
        val reader = ClassReader(entry.contents)
        val writer = ClassWriter(reader, 0)
        reader.accept(AccessTransformerVisitor(at, context.inheritanceProvider(), writer), 0)
        return JarClassEntry(entry.name, entry.time, writer.toByteArray())
    }
}

class AccessTransformerVisitor(
    private val at: AccessTransformSet,
    private val inheritanceProvider: InheritanceProvider,
    writer: ClassWriter
) : ClassVisitor(Opcodes.ASM7, writer) {

    private lateinit var classTransform: AccessTransformSet.Class

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        classTransform = completedClassAt(name)
        super.visit(version, classTransform.get().apply(access), name, signature, superName, interfaces)
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        return super.visitField(classTransform.getField(name).apply(access), name, descriptor, signature, value)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val newAccess = classTransform.getMethod(MethodSignature.of(name, descriptor)).apply(access)
        return super.visitMethod(newAccess, name, descriptor, signature, exceptions)
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        super.visitInnerClass(name, outerName, innerName, completedClassAt(name).get().apply(access))
    }

    private fun completedClassAt(className: String): AccessTransformSet.Class =
        at.getOrCreateClass(className).apply { complete(inheritanceProvider) }
}

private const val RESET_ACCESS: Int = (Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()
fun AccessTransform?.apply(currentModifier: Int): Int {
    if (this == null) {
        return currentModifier
    }
    var value = currentModifier
    if (this.access != AccessChange.NONE) {
        value = value and RESET_ACCESS
        value = value or this.access.modifier
    }
    when (this.final) {
        ModifierChange.REMOVE -> {
            value = value and Opcodes.ACC_FINAL.inv()
        }
        ModifierChange.ADD -> {
            value = value or Opcodes.ACC_FINAL
        }
        else -> {
        }
    }
    return value
}
