/*
 * Copyright 2018 Kyle Wood
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.unzip
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.extra.BridgeMethodRewriter
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.cadixdev.mercury.util.BombeBindings
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.property
import java.io.File

open class RemapSources : ZippedTask() {

    @InputFile
    val vanillaJar = project.objects.fileProperty()
    @InputFile
    val vanillaRemappedSpigotJar = project.objects.fileProperty() // Required for pre-remap pass
    @InputFile
    val vanillaRemappedSrgJar = project.objects.fileProperty() // Required for post-remap pass
    @InputFile
    val spigotToSrg = project.objects.fileProperty()
    @InputFile
    val spigotApiZip = project.objects.fileProperty()
    @Input
    val config = project.objects.property<String>()
    @InputFile
    val constructors = project.objects.fileProperty()

    @OutputFile
    val generatedAt = project.objects.fileProperty()

    override fun action(rootDir: File) {
        val spigotToSrgFile = spigotToSrg.asFile.get()

        val mappings = spigotToSrgFile.bufferedReader().use { reader ->
            MappingFormats.TSRG.createReader(reader).read()
        }

        val srcDir = rootDir.resolve("src/main/java")
        val pass1Dir = rootDir.resolve("pass1")
        val outDir = rootDir.resolve("out")

        val configuration = project.configurations[config.get()]

        val ats = AccessTransformSet.create()

        val spigotApiDir = unzip(spigotApiZip)
        try {
            val merc = Mercury()

            merc.classPath.addAll(listOf(
                vanillaJar.asFile.get().toPath(),
                vanillaRemappedSpigotJar.asFile.get().toPath(),
                spigotApiDir.resolve("src/main/java").toPath()
            ))

            for (file in configuration.resolvedConfiguration.files) {
                merc.classPath.add(file.toPath())
            }

            // Generate AT
            merc.processors.add(AccessAnalyzerProcessor.create(ats, mappings))
            merc.process(srcDir.toPath())

            // Remap
            merc.processors.clear()
            merc.processors.addAll(listOf(
                MercuryRemapper.create(mappings),
                BridgeMethodRewriter.create(),
                AccessTransformerRewriter.create(ats)
            ))

            merc.rewrite(srcDir.toPath(), pass1Dir.toPath())

            // Remap SRG parameters
            merc.classPath.clear()
            merc.classPath.addAll(listOf(
                vanillaJar.asFile.get().toPath(),
                vanillaRemappedSrgJar.asFile.get().toPath(),
                spigotApiDir.resolve("src/main/java").toPath()
            ))

            merc.processors.clear()
            merc.processors.add(SrgParameterRemapper(project.file(constructors)))

            merc.rewrite(pass1Dir.toPath(), outDir.toPath())
        } finally {
            spigotApiDir.deleteRecursively()
        }

        // Only leave remapped source
        val fileList = rootDir.listFiles() ?: throw PaperweightException("Could not list files in $rootDir")
        for (file in fileList) {
            if (file != outDir) {
                file.deleteRecursively()
            }
        }

        // Move out/* to base dir
        val files = outDir.listFiles() ?: throw PaperweightException("No files found in $outDir")
        for (file in files) {
            file.renameTo(rootDir.resolve(file.name))
        }
        outDir.delete()

        // And write generated AT file
        AccessTransformFormats.FML.write(generatedAt.asFile.get().toPath(), ats)
    }
}

class SrgParameterRemapper(constructors: File) : SourceRewriter {

    private val constructorMap = hashMapOf<String, MutableList<ConstructorNode>>()

    init {
        constructors.useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(' ')
                constructorMap.compute(parts[1].replace('/', '.')) { _, v ->
                    val node = ConstructorNode(parts[0].toInt(), parts[2])
                    if (v == null) {
                        return@compute mutableListOf(node)
                    } else {
                        v += node
                        return@compute v
                    }
                }
            }
        }

        for (list in constructorMap.values) {
            // Put bigger numbers first...just trust me
            list.reverse()
        }
    }

    override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

    override fun rewrite(context: RewriteContext) {
        context.compilationUnit.accept(SrgParameterVisitor(context, constructorMap))
    }
}

data class ConstructorNode(
    val id: Int,
    val signature: String
)

class SrgParameterVisitor(
    private val context: RewriteContext,
    private val constructors: Map<String, List<ConstructorNode>>
) : ASTVisitor() {

    companion object {
        private val MATCHER = Regex("func_(\\d+)_.*")
    }

    override fun visit(node: SimpleName): Boolean {
        val binding = node.resolveBinding() as? IVariableBinding ?: return false
        if (!binding.isParameter) {
            return false
        }

        val methodDecl = findMethodDeclaration(node) ?: return false
        val method = binding.declaringMethod ?: return false

        handleMethod(node, methodDecl, method)
        return false
    }

    private fun handleMethod(node: SimpleName, methodDecl: MethodDeclaration, method: IMethodBinding) {
        if (method.isConstructor) {
            handleConstructor(node, methodDecl, method)
            return
        }

        val match = MATCHER.matchEntire(method.name) ?: return
        val isStatic = method.modifiers and Modifier.STATIC != 0

        val index = getParameterIndex(methodDecl, node)
        if (index == -1) {
            return
        }

        val paramName = "p_${match.groupValues[1]}_${index + if (isStatic) 0 else 1}_"
        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, paramName, null)
    }

    private fun handleConstructor(node: SimpleName, methodDecl: MethodDeclaration, method: IMethodBinding) {
        val constructorNodes = constructors[method.declaringClass.binaryName] ?: return

        val constructorNode = constructorNodes.firstOrNull { constructorNode ->
            constructorNode.signature == BombeBindings.convertSignature(method).descriptor.toString()
        } ?: return

        val id = constructorNode.id

        val index = getParameterIndex(methodDecl, node)
        if (index == -1) {
            return
        }

        val paramName = "p_i${id}_${index + 1}_"
        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, paramName, null)
    }

    private fun findMethodDeclaration(node: ASTNode): MethodDeclaration? {
        var currentNode: ASTNode? = node
        while (currentNode != null && currentNode !is MethodDeclaration) {
            currentNode = currentNode.parent
        }
        return currentNode as? MethodDeclaration
    }

    private fun getParameterIndex(methodDecl: MethodDeclaration, name: SimpleName): Int {
        // Can't find a better way to get the index..
        @Suppress("UNCHECKED_CAST")
        val params = methodDecl.parameters() as List<SingleVariableDeclaration>
        return params.indexOfFirst { it.name.identifier == name.identifier }
    }
}
