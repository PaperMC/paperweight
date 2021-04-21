/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
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

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.isLibraryJar
import io.papermc.paperweight.util.path
import java.io.File
import javax.inject.Inject
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Initializer
import org.eclipse.jdt.core.dom.LambdaExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.MethodReference
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.Name
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SuperFieldAccess
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.ThisExpression
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

abstract class RemapSources : ZippedTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:InputFile
    abstract val vanillaRemappedSpigotJar: RegularFileProperty

    @get:InputFile
    abstract val mappings: RegularFileProperty

    @get:InputDirectory
    abstract val spigotDeps: DirectoryProperty

    @get:InputDirectory
    abstract val spigotServerDir: DirectoryProperty

    @get:InputDirectory
    abstract val spigotApiDir: DirectoryProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()
        generatedAt.convention(defaultOutput("at"))
    }

    override fun run(rootDir: File) {
        val srcDir = spigotServerDir.file.resolve("src/main/java")

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx2G")
        }

        queue.submit(RemapAction::class) {
            classpath.add(vanillaRemappedSpigotJar.file)
            classpath.add(vanillaJar.file)
            classpath.add(spigotApiDir.dir("src/main/java").get().asFile)
            classpath.addAll(spigotDeps.get().asFileTree.filter { it.isLibraryJar }.files)

            mappings.set(this@RemapSources.mappings.file)
            inputDir.set(srcDir)

            outputDir.set(rootDir)
            generatedAtOutput.set(generatedAt.file)
        }

        queue.await()
    }

    abstract class RemapAction : WorkAction<RemapParams> {
        override fun execute() {
            val mappingSet = MappingFormats.TINY.read(
                parameters.mappings.path,
                Constants.SPIGOT_NAMESPACE,
                Constants.DEOBF_NAMESPACE
            )
            val processAt = AccessTransformSet.create()

            // Remap any references Spigot maps to mojmap+yarn
            Mercury().let { merc ->
                merc.classPath.addAll(parameters.classpath.get().map { it.toPath() })

                merc.processors += AccessAnalyzerProcessor.create(processAt, mappingSet)

                merc.process(parameters.inputDir.path)

                merc.processors.clear()
                merc.processors.addAll(
                    listOf(
                        ExplicitThisAdder,
                        MercuryRemapper.create(mappingSet),
                        AccessTransformerRewriter.create(processAt)
                    )
                )

                merc.rewrite(parameters.inputDir.path, parameters.outputDir.path)
            }

            AccessTransformFormats.FML.write(parameters.generatedAtOutput.path, processAt)
        }
    }

    interface RemapParams : WorkParameters {
        val classpath: ListProperty<File>
        val mappings: RegularFileProperty
        val inputDir: RegularFileProperty

        val generatedAtOutput: RegularFileProperty
        val outputDir: RegularFileProperty
    }

    object ExplicitThisAdder : SourceRewriter {

        override fun getFlags(): Int = SourceProcessor.FLAG_RESOLVE_BINDINGS

        override fun rewrite(context: RewriteContext) {
            context.compilationUnit.accept(ExplicitThisAdderVisitor(context))
        }
    }

    class ExplicitThisAdderVisitor(private val context: RewriteContext) : ASTVisitor() {

        override fun visit(node: SimpleName): Boolean {
            val binding = node.resolveBinding() ?: return false

            val name = when (val declaringNode = context.compilationUnit.findDeclaringNode(binding)) {
                is VariableDeclarationFragment -> declaringNode.name
                is MethodDeclaration -> declaringNode.name
                else -> return false
            }
            if (name === node) {
                // this is the actual declaration
                return false
            }

            visit(node, binding)
            return false
        }

        private fun visit(node: SimpleName, binding: IBinding) {
            if (binding.kind != IBinding.VARIABLE && binding.kind != IBinding.METHOD) {
                return
            }

            val referringClass = when (binding) {
                is IVariableBinding -> {
                    if (!binding.isField || binding.isEnumConstant) {
                        return
                    }
                    binding.declaringClass
                }
                is IMethodBinding -> {
                    if (binding.isConstructor || binding.isSynthetic) {
                        return
                    }
                    binding.declaringClass
                }
                else -> return
            }
            val modifiers = when (binding) {
                is IVariableBinding -> binding.modifiers
                is IMethodBinding -> binding.modifiers
                else -> return
            }

            when (val p = node.parent) {
                is FieldAccess, is SuperFieldAccess, is QualifiedName, is ThisExpression, is MethodReference, is SuperMethodInvocation -> return
                is MethodInvocation -> {
                    if (p.expression != null && p.expression !== node) {
                        return
                    }
                }
            }

            // find declaring method
            var parentNode: ASTNode? = node
            loop@while (parentNode != null) {
                when (parentNode) {
                    is MethodDeclaration, is AnonymousClassDeclaration, is LambdaExpression, is Initializer -> break@loop
                }
                parentNode = parentNode.parent
            }

            val rewrite = context.createASTRewrite()
            val fieldAccess = rewrite.ast.newFieldAccess()

            val expr: Expression = if (!Modifier.isStatic(modifiers)) {
                rewrite.ast.newThisExpression().also { thisExpr ->
                    if (parentNode is LambdaExpression) {
                        return@also
                    }

                    if (parentNode is AnonymousClassDeclaration && referringClass.erasure != parentNode.resolveBinding().erasure) {
                        val name = getNameNode(referringClass) ?: return
                        thisExpr.qualifier = rewrite.createCopyTarget(name) as Name
                        return@also
                    }

                    val methodDec = parentNode as? MethodDeclaration ?: return@also

                    var methodClass = methodDec.resolveBinding().declaringClass
                    if (referringClass.erasure != methodClass.erasure && methodClass.isNested && !Modifier.isStatic(methodClass.modifiers)) {
                        while (true) {
                            methodClass = methodClass.declaringClass ?: break
                        }
                        // Looks like the method is accessing an outer class's fields
                        if (referringClass.erasure == methodClass.erasure) {
                            val name = getNameNode(referringClass) ?: return
                            thisExpr.qualifier = rewrite.createCopyTarget(name) as Name
                        }
                    }
                }
            } else {
                if (parentNode is Initializer && Modifier.isStatic(parentNode.modifiers)) {
                    // Can't provide explicit static receiver here
                    return
                }
                val name = getNameNode(referringClass) ?: return
                rewrite.createCopyTarget(name) as Name
            }

            fieldAccess.expression = expr
            fieldAccess.name = rewrite.createMoveTarget(node) as SimpleName

            rewrite.replace(node, fieldAccess, null)
        }

        private fun getNameNode(dec: ITypeBinding): Name? {
            val typeDec = context.compilationUnit.findDeclaringNode(dec) as? TypeDeclaration ?: return null
            return typeDec.name
        }
    }
}
