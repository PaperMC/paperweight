/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.mcpFile
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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.property
import java.io.File

open class RemapSources : ZippedTask() {

    @InputFile
    val vanillaJar: RegularFileProperty = project.objects.fileProperty()
    @InputFile
    val vanillaRemappedSpigotJar: RegularFileProperty = project.objects.fileProperty() // Required for pre-remap pass
    @InputFile
    val vanillaRemappedSrgJar: RegularFileProperty = project.objects.fileProperty() // Required for post-remap pass
    @InputFile
    val spigotToSrg: RegularFileProperty = project.objects.fileProperty()
    @Input
    val configuration: Property<String> = project.objects.property()
    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()
    @InputDirectory
    val spigotServerDir: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val spigotApiDir: DirectoryProperty = project.objects.directoryProperty()

    @OutputFile
    val generatedAt: RegularFileProperty = project.objects.fileProperty()

    override fun run(rootDir: File) {
        val config = mcpConfig(configFile)
        val constructors = mcpFile(configFile, config.data.constructors)

        val spigotToSrgFile = spigotToSrg.file

        val mappings = spigotToSrgFile.bufferedReader().use { reader ->
            MappingFormats.TSRG.createReader(reader).read()
        }

        val srcDir = spigotServerDir.file.resolve("src/main/java")
        val pass1Dir = rootDir.resolve("pass1")
        val outDir = rootDir.resolve("out")

        val configuration = project.configurations[configuration.get()]

        val ats = AccessTransformSet.create()

        Mercury().apply {
            classPath.addAll(listOf(
                vanillaJar.asFile.get().toPath(),
                vanillaRemappedSpigotJar.asFile.get().toPath(),
                spigotApiDir.file.resolve("src/main/java").toPath()
            ))

            for (file in configuration.resolvedConfiguration.files) {
                classPath.add(file.toPath())
            }

            // Generate AT
            processors.add(AccessAnalyzerProcessor.create(ats, mappings))
            process(srcDir.toPath())

            // Remap
            processors.clear()
            processors.addAll(listOf(
                MercuryRemapper.create(mappings),
                BridgeMethodRewriter.create(),
                AccessTransformerRewriter.create(ats)
            ))

            rewrite(srcDir.toPath(), pass1Dir.toPath())
        }

        Mercury().apply {
            // Remap SRG parameters
            classPath.addAll(listOf(
                vanillaJar.asFile.get().toPath(),
                vanillaRemappedSrgJar.asFile.get().toPath(),
                spigotApiDir.file.resolve("src/main/java").toPath()
            ))

            processors.add(SrgParameterRemapper(constructors))

            rewrite(pass1Dir.toPath(), outDir.toPath())
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
