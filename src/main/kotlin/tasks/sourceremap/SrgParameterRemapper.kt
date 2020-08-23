package io.papermc.paperweight.tasks.sourceremap

import io.papermc.paperweight.shared.ConstructorNode
import io.papermc.paperweight.shared.ConstructorsData
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
    private val parameterNames: MutableMap<String, Array<String?>>? = null
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
    private val paramMap: MutableMap<String, Array<String?>>?
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
        paramMap?.let { map ->
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

