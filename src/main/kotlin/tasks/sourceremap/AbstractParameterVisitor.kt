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

import org.cadixdev.mercury.RewriteContext
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.VariableDeclaration

abstract class AbstractParameterVisitor(protected val context: RewriteContext) : ASTVisitor() {

    override fun visit(node: SimpleName): Boolean {
        val binding = node.resolveBinding() as? IVariableBinding ?: return false
        if (!binding.isParameter) {
            return false
        }

        val variableDecl = context.compilationUnit.findDeclaringNode(binding.variableDeclaration) as VariableDeclaration

        val method = binding.declaringMethod
        val methodDecl = context.compilationUnit.findDeclaringNode(method) as? MethodDeclaration ?: return false

        if (method.isConstructor) {
            handleConstructor(node, methodDecl, method, variableDecl)
        } else {
            handleMethod(node, methodDecl, method, variableDecl)
        }

        return false
    }

    abstract fun handleMethod(
        node: SimpleName,
        methodDecl: MethodDeclaration,
        method: IMethodBinding,
        variableDecl: VariableDeclaration
    )

    abstract fun handleConstructor(
        node: SimpleName,
        methodDecl: MethodDeclaration,
        method: IMethodBinding,
        variableDecl: VariableDeclaration
    )

    fun getParameterIndex(methodDecl: MethodDeclaration, decl: VariableDeclaration): Int {
        @Suppress("UNCHECKED_CAST")
        val params = methodDecl.parameters() as List<VariableDeclaration>
        return params.indexOfFirst { it === decl }
    }
}
