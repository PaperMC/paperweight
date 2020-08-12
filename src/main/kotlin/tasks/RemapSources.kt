/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
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

import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.mcpFile
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
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
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.VariableDeclaration
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
    val mappings: RegularFileProperty = project.objects.fileProperty()
    @Input
    val configuration: Property<String> = project.objects.property()
    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()
    @InputDirectory
    val spigotServerDir: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val spigotApiDir: DirectoryProperty = project.objects.directoryProperty()

    @OutputFile
    val generatedAt: RegularFileProperty = defaultOutput("at")

    override fun run(rootDir: File) {
        val config = mcpConfig(configFile)
        val constructors = mcpFile(configFile, config.data.constructors)

        val mappings = MappingFormats.TSRG.createReader(mappings.file.toPath()).use { it.read() }

        val srcDir = spigotServerDir.file.resolve("src/main/java")

        val configuration = project.configurations[configuration.get()]

        val ats = AccessTransformSet.create()

        // Remap any references Spigot maps to SRG
        Mercury().apply {
            classPath.addAll(listOf(
                vanillaJar.file.toPath(),
                vanillaRemappedSpigotJar.file.toPath(),
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
                AccessTransformerRewriter.create(ats),
                SrgParameterRemapper(mappings, constructors)
            ))

            rewrite(srcDir.toPath(), rootDir.toPath())
        }

        // And write generated AT file
        AccessTransformFormats.FML.write(generatedAt.asFile.get().toPath(), ats)
    }
}

class SrgParameterRemapper(private val mappings: MappingSet, constructors: File) : SourceRewriter {

    private val constructorMap = hashMapOf<String, MutableList<ConstructorNode>>()

    init {
        constructors.useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(' ')
                constructorMap.compute(parts[1]) { _, v ->
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
            // Put bigger numbers first
            // Old constructor entries are still present, just with smaller numbers. So we don't want to grab an older
            // entry
            list.reverse()
        }
    }

    override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

    override fun rewrite(context: RewriteContext) {
        context.compilationUnit.accept(SrgParameterVisitor(context, mappings, constructorMap))
    }
}

data class ConstructorNode(
    val id: Int,
    val signature: String
)

class SrgParameterVisitor(
    private val context: RewriteContext,
    private val mappings: MappingSet,
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

        val variableDecl = context.compilationUnit.findDeclaringNode(binding.variableDeclaration) as VariableDeclaration

        val method = binding.declaringMethod
        val methodDecl = context.compilationUnit.findDeclaringNode(method) as? MethodDeclaration ?: return false

        handleMethod(node, methodDecl, method, variableDecl)

        return false
    }

    private fun handleMethod(node: SimpleName, methodDecl: MethodDeclaration, method: IMethodBinding, variableDecl: VariableDeclaration) {
        if (method.isConstructor) {
            handleConstructor(node, methodDecl, method, variableDecl)
            return
        }

        val methodName = mappings.getClassMapping(method.declaringClass.binaryName.replace('.', '/'))
            .flatMap { it.getMethodMapping(BombeBindings.convertSignature(method)) }
            .map { it.deobfuscatedName }
            .orElse(method.name)

        val match = MATCHER.matchEntire(methodName) ?: return
        val isStatic = method.modifiers and Modifier.STATIC != 0

        var index = getParameterIndex(methodDecl, variableDecl)
        if (index == -1) {
            return
        }
        if (!isStatic) {
            index++
        }

        val paramName = "p_${match.groupValues[1]}_${index}_"
        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, paramName, null)
    }

    private fun handleConstructor(node: SimpleName, methodDecl: MethodDeclaration, method: IMethodBinding, variableDecl: VariableDeclaration) {
        val binaryName = method.declaringClass.binaryName.replace('.', '/')
        val className = mappings.getClassMapping(binaryName)
            .map { it.fullDeobfuscatedName }
            .orElse(binaryName)

        val constructorNodes = constructors[className] ?: return

        val constructorNode = constructorNodes.firstOrNull { constructorNode ->
            constructorNode.signature == BombeBindings.convertSignature(method).descriptor.toString()
        } ?: return

        val id = constructorNode.id

        var index = getParameterIndex(methodDecl, variableDecl)
        if (index == -1) {
            return
        }
        // Constructors are never static
        index++

        val paramName = "p_i${id}_${index}_"
        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, paramName, null)
    }

    private fun getParameterIndex(methodDecl: MethodDeclaration, decl: VariableDeclaration): Int {
        @Suppress("UNCHECKED_CAST")
        val params = methodDecl.parameters() as List<VariableDeclaration>
        return params.indexOfFirst { it === decl }
    }
}
