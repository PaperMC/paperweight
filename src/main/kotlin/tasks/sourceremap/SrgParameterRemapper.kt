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

package io.papermc.paperweight.tasks.sourceremap

import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.util.BombeBindings
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.VariableDeclaration

class SrgParameterRemapper(
    private val mappings: MappingSet,
    private val constructorsData: ConstructorsData,
    private val parameterNames: ParamNames? = null
) : SourceRewriter {

    override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

    override fun rewrite(context: RewriteContext) {
        context.compilationUnit.accept(SrgParameterVisitor(context, mappings, constructorsData, parameterNames))
    }
}

class SrgParameterVisitor(
    context: RewriteContext,
    private val mappings: MappingSet,
    private val constructorsData: ConstructorsData,
    private val paramNames: ParamNames?
) : AbstractParameterVisitor(context) {

    companion object {
        private val MATCHER = Regex("func_(\\d+)_.*")
    }

    override fun handleMethod(
        node: SimpleName,
        methodDecl: MethodDeclaration,
        method: IMethodBinding,
        variableDecl: VariableDeclaration
    ) {
        val methodName = mappings.getClassMapping(method.declaringClass.binaryName)
            .flatMap { it.getMethodMapping(BombeBindings.convertSignature(method)) }
            .map { it.deobfuscatedName }
            .orElse(null) ?: return

        val match = MATCHER.matchEntire(methodName) ?: return
        val isStatic = method.modifiers and Modifier.STATIC != 0

        var index = getParameterIndex(methodDecl, variableDecl)
        if (index == -1) {
            return
        }

        recordName(methodName, method, node, index)

        if (!isStatic) {
            index++
        }

        val paramName = "p_${match.groupValues[1]}_${index}_"
        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, paramName, null)
    }

    override fun handleConstructor(
        node: SimpleName,
        methodDecl: MethodDeclaration,
        method: IMethodBinding,
        variableDecl: VariableDeclaration
    ) {
        val binaryName = method.declaringClass.binaryName
        val classMapping = mappings.getClassMapping(binaryName)
        val className = classMapping
            .map { it.fullDeobfuscatedName }
            .orElse(binaryName)

        val descriptor = BombeBindings.convertSignature(method).descriptor
        val constructorNode = constructorsData.findConstructorNode(className, descriptor) ?: return

        val id = constructorNode.id

        var index = getParameterIndex(methodDecl, variableDecl)
        if (index == -1) {
            return
        }

        recordName("const_$id", method, node, index)

        // Constructors are never static
        index++

        val paramName = "p_i${id}_${index}_"
        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, paramName, null)
    }

    private fun recordName(
        methodName: String,
        method: IMethodBinding,
        node: SimpleName,
        index: Int
    ) {
        paramNames?.let { map ->
            val paramCount = method.parameterTypes.size
            map.computeIfAbsent(methodName) { arrayOfNulls(paramCount) }[index] = node.identifier
        }
    }
}

fun ConstructorsData.findConstructorNode(className: String, desc: MethodDescriptor): ConstructorNode? {
    val constructorNodes = constructors[className] ?: return null

    val descriptorText = desc.toString()
    return constructorNodes.firstOrNull { constructorNode ->
        constructorNode.descriptor == descriptorText
    }
}

