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
