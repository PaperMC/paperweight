/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.streams.asSequence
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.RewriteContext
import org.cadixdev.mercury.SourceProcessor
import org.cadixdev.mercury.SourceRewriter
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.remapper.MercuryRemapper
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class RemapSources : JavaLauncherTask() {

    @get:CompileClasspath
    abstract val vanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val mojangMappedVanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val vanillaRemappedSpigotJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:CompileClasspath
    abstract val spigotDeps: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spigotServerDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spigotApiDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalAts: RegularFileProperty

    @get:OutputFile
    abstract val generatedAt: RegularFileProperty

    @get:OutputFile
    abstract val sourcesOutputZip: RegularFileProperty

    @get:OutputFile
    abstract val testsOutputZip: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:OutputFile
    abstract val spigotRecompiledClasses: RegularFileProperty

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx2G"))
        sourcesOutputZip.convention(defaultOutput("$name-sources", "jar"))
        testsOutputZip.convention(defaultOutput("$name-tests", "jar"))
        generatedAt.convention(defaultOutput("at"))
        spigotRecompiledClasses.convention(defaultOutput("spigotRecompiledClasses", "txt"))
    }

    @TaskAction
    fun run() {
        val srcOut = findOutputDir(sourcesOutputZip.path).apply { createDirectories() }
        val testOut = findOutputDir(testsOutputZip.path).apply { createDirectories() }

        try {
            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs(jvmargs.get())
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            val srcDir = spigotServerDir.path.resolve("src/main/java")

            // Remap sources
            queue.submit(RemapAction::class) {
                classpath.from(vanillaRemappedSpigotJar.path)
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(spigotApiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                additionalAts.set(this@RemapSources.additionalAts.pathOrNull)

                mappings.set(this@RemapSources.mappings.path)
                inputDir.set(srcDir)

                cacheDir.set(this@RemapSources.layout.cache)

                outputDir.set(srcOut)
                generatedAtOutput.set(generatedAt.path)
            }

            val testSrc = spigotServerDir.path.resolve("src/test/java")

            // Remap tests
            queue.submit(RemapAction::class) {
                classpath.from(vanillaRemappedSpigotJar.path)
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(spigotApiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                classpath.from(srcDir)
                additionalAts.set(this@RemapSources.additionalAts.pathOrNull)

                mappings.set(this@RemapSources.mappings.path)
                inputDir.set(testSrc)

                cacheDir.set(this@RemapSources.layout.cache)

                outputDir.set(testOut)
            }

            queue.await()

            zip(srcOut, sourcesOutputZip)
            zip(testOut, testsOutputZip)

            writeSpigotRecompiledFiles(srcOut)
        } finally {
            srcOut.deleteRecursively()
            testOut.deleteRecursively()
        }
    }

    private fun writeSpigotRecompiledFiles(srcOut: Path) {
        // Write list of java files spigot recompiles
        val spigotRecompiled = Files.walk(srcOut).use { stream ->
            stream.asSequence().mapNotNull {
                if (!it.isRegularFile()) {
                    return@mapNotNull null
                }
                if (!it.fileName.pathString.endsWith(".java")) {
                    return@mapNotNull null
                }
                val path = srcOut.relativize(it).pathString
                if (!path.startsWith("net/minecraft")) {
                    return@mapNotNull null
                }
                path.replace(".java", "")
            }.sorted().joinToString("\n")
        }
        spigotRecompiledClasses.path.parent.createDirectories()
        spigotRecompiledClasses.path.writeText(spigotRecompiled)
    }

    abstract class RemapAction : WorkAction<RemapParams> {
        override fun execute() {
            val mappingSet = MappingFormats.TINY.read(
                parameters.mappings.path,
                SPIGOT_NAMESPACE,
                DEOBF_NAMESPACE
            )

            val additionalAt = parameters.additionalAts.pathOrNull?.let { AccessTransformFormats.FML.read(it) }

            val processAt = AccessTransformSet.create()
            val generatedAtOutPath = parameters.generatedAtOutput.pathOrNull

            // Remap any references Spigot maps to mojmap+yarn
            Mercury().let { merc ->
                merc.sourceCompatibility = JavaCore.VERSION_17
                merc.isGracefulClasspathChecks = true
                merc.classPath.addAll(parameters.classpath.map { it.toPath() })

                if (generatedAtOutPath != null) {
                    merc.processors += AccessAnalyzerProcessor.create(processAt, mappingSet)
                }

                merc.process(parameters.inputDir.path)

                val tempOut = Files.createTempDirectory(parameters.cacheDir.path, "remap")
                try {
                    merc.processors.clear()
                    merc.processors.addAll(
                        listOf(
                            ExplicitThisAdder,
                            MercuryRemapper.create(mappingSet),
                            AccessTransformerRewriter.create(processAt)
                        )
                    )

                    if (generatedAtOutPath != null) {
                        merc.processors.add(AccessTransformerRewriter.create(processAt))
                    }

                    merc.rewrite(parameters.inputDir.path, tempOut)

                    if (additionalAt != null) {
                        merc.processors.clear()
                        merc.processors += AccessTransformerRewriter.create(additionalAt)

                        merc.rewrite(tempOut, parameters.outputDir.path)
                    } else {
                        tempOut.copyRecursivelyTo(parameters.outputDir.path)
                    }
                } finally {
                    tempOut.deleteRecursively()
                }
            }

            if (generatedAtOutPath != null) {
                AccessTransformFormats.FML.write(generatedAtOutPath, processAt)
            }
        }
    }

    interface RemapParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val mappings: RegularFileProperty
        val inputDir: RegularFileProperty
        val additionalAts: RegularFileProperty

        val cacheDir: RegularFileProperty
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
                null -> null
                else -> return false
            }
            if (name != null && name === node) {
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
                is FieldAccess, is SuperFieldAccess, is ThisExpression, is MethodReference, is SuperMethodInvocation, is MemberValuePair -> return
                is MethodInvocation -> {
                    if (!p.arguments().contains(node)) {
                        if (p.expression != null && p.expression !== node) {
                            return
                        }
                    }
                }
                is QualifiedName -> {
                    if (p.qualifier !== node) {
                        return
                    }
                }
                is ClassInstanceCreation -> {
                    if (!p.arguments().contains(node)) {
                        return
                    }
                }
            }

            val rewrite = context.createASTRewrite()
            val fieldAccess = rewrite.ast.newFieldAccess()

            val expr: Expression = if (!Modifier.isStatic(modifiers)) {
                val accessible = mutableListOf<ITypeBinding>()
                var curr: ASTNode = node
                while (true) {
                    if (curr is TypeDeclaration) {
                        accessible += curr.resolveBinding() ?: break
                    } else if (curr is AnonymousClassDeclaration) {
                        accessible += curr.resolveBinding() ?: break
                    }
                    val m = when (curr) {
                        is MethodDeclaration -> curr.modifiers
                        is FieldDeclaration -> curr.modifiers
                        is Initializer -> curr.modifiers
                        is TypeDeclaration -> curr.modifiers
                        else -> null
                    }
                    if (m != null && Modifier.isStatic(m)) {
                        break
                    }
                    curr = curr.parent ?: break
                }

                rewrite.ast.newThisExpression().also { thisExpr ->
                    if (accessible.size == 1) {
                        return@also
                    }
                    val accessibleTargetCls = accessible.find { referringClass.isCastCompatible(it) }
                        ?: return@also
                    if (accessibleTargetCls.isAnonymous) {
                        if (accessible.indexOf(accessibleTargetCls) == 0) {
                            return@also
                        }
                        return
                    }

                    if (!accessibleTargetCls.isCastCompatible(accessible[0])) {
                        val name = getNameNode(accessibleTargetCls)
                            ?: throw PaperweightException("Could not find name node for ${accessibleTargetCls.qualifiedName}")
                        thisExpr.qualifier = rewrite.createCopyTarget(name) as Name
                    }
                }
            } else {
                // find declaring method
                var parentNode: ASTNode? = node
                loop@ while (parentNode != null) {
                    when (parentNode) {
                        is MethodDeclaration, is AnonymousClassDeclaration, is LambdaExpression, is Initializer -> break@loop
                    }
                    parentNode = parentNode.parent
                }

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
