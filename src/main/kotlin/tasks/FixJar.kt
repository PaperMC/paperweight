package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.AsmUtil
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import java.io.ByteArrayInputStream
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

abstract class FixJar : BaseTask(), AsmUtil {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        JarOutputStream(outputJar.file.outputStream()).use { out ->
            JarFile(inputJar.file).use { jarFile ->
                for (entry in jarFile.entries()) {
                    if (!entry.name.endsWith(".class")) {
                        out.putNextEntry(entry)
                        try {
                            jarFile.getInputStream(entry).copyTo(out)
                        } finally {
                            out.closeEntry()
                        }
                        continue
                    }

                    val classData = if (entry.size != -1L) {
                        ByteArray(entry.size.toInt()).also { data ->
                            jarFile.getInputStream(entry).readNBytes(data, 0, data.size)
                        }
                    } else {
                        jarFile.getInputStream(entry).readAllBytes()
                    }

                    try {
                        val node = ClassNode(Opcodes.ASM9)
                        var visitor: ClassVisitor = node
                        visitor = ParameterAnnotationFixer(node, visitor)

                        val reader = ClassReader(classData)
                        reader.accept(visitor, 0)

                        val writer = ClassWriter(0)
                        node.accept(writer)

                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(writer.toByteArray())
                        out.flush()
                    } finally {
                        out.closeEntry()
                    }
                }
            }
        }
    }
}

class ParameterAnnotationFixer(
    private val node: ClassNode,
    classVisitor: ClassVisitor?
) : ClassVisitor(Opcodes.ASM9, classVisitor), AsmUtil {

    override fun visitEnd() {
        super.visitEnd()

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

        return listOf(Type.getObjectType(innerNode.outerName))
    }

    private fun processConstructor(method: MethodNode, synthParams: List<Type>) {
        val params = Type.getArgumentTypes(method.desc).asList()

        if (!params.beginsWith(synthParams)) {
            return
        }

        method.visibleParameterAnnotations = process(params.size, synthParams.size, method.visibleParameterAnnotations)
        method.invisibleParameterAnnotations = process(params.size, synthParams.size, method.invisibleParameterAnnotations)

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
