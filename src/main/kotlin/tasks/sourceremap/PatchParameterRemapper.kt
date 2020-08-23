package io.papermc.paperweight.tasks.sourceremap

import io.papermc.paperweight.shared.ConstructorsData
import io.papermc.paperweight.shared.PaperweightException
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.util.BombeBindings
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.VariableDeclaration

class PatchParameterRemapper(
    private val paramNames: Map<String, Array<String?>>,
    private val constructorsData: ConstructorsData
) : SourceRewriter {
    override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

    override fun rewrite(context: RewriteContext) {
        context.compilationUnit.accept(PatchParameterVisitor(context, paramNames, constructorsData))
    }
}

class PatchParameterVisitor(
    context: RewriteContext,
    private val paramNames: Map<String, Array<String?>>,
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
