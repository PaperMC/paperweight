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

import io.papermc.paperweight.PaperweightException
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.util.BombeBindings
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.VariableDeclaration

class PatchParameterRemapper(
    private val paramNames: ParamNames,
    private val constructorsData: ConstructorsData
) : SourceRewriter {
    override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

    override fun rewrite(context: RewriteContext) {
        context.compilationUnit.accept(PatchParameterVisitor(context, paramNames, constructorsData))
    }
}

class PatchParameterVisitor(
    context: RewriteContext,
    private val paramNames: ParamNames,
    private val constructorsData: ConstructorsData
) : AbstractParameterVisitor(context) {

    override fun handleMethod(
        node: SimpleName,
        methodDecl: MethodDeclaration,
        method: IMethodBinding,
        variableDecl: VariableDeclaration
    ) {
        val paramNames = paramNames[methodDecl.name.identifier] ?: return
        val params = methodDecl.parameters()

        if (paramNames.size != params.size) {
            throw PaperweightException("Invalid parameter length; expected ${paramNames.size}, actual ${params.size} " +
                "for method ${methodDecl.name.identifier}")
        }

        val index = getParameterIndex(methodDecl, variableDecl)
        val newName = paramNames[index] ?: return

        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, newName, null)
    }

    override fun handleConstructor(
        node: SimpleName,
        methodDecl: MethodDeclaration,
        method: IMethodBinding,
        variableDecl: VariableDeclaration
    ) {
        val className = method.declaringClass.binaryName.replace('.', '/')
        val descriptor = BombeBindings.convertSignature(method).descriptor

        val constructorNode = constructorsData.findConstructorNode(className, descriptor) ?: return
        val paramNames = paramNames["const_${constructorNode.id}"] ?: return
        val params = methodDecl.parameters()

        if (paramNames.size != params.size) {
            throw PaperweightException("Invalid parameter length; expected ${paramNames.size}, actual ${params.size} " +
                "for constructor $className $descriptor")
        }

        val index = getParameterIndex(methodDecl, variableDecl)
        val newName = paramNames[index]

        context.createASTRewrite().set(node, SimpleName.IDENTIFIER_PROPERTY, newName, null)
    }
}
