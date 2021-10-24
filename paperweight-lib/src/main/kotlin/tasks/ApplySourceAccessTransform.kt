/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

import io.papermc.paperweight.util.*
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
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
abstract class ApplySourceAccessTransform : JavaLauncherTask() {

    @get:CompileClasspath
    abstract val vanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val mojangMappedVanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val spigotDeps: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val accessTransforms: RegularFileProperty

    @get:OutputFile
    abstract val sourcesOutputZip: RegularFileProperty

    @get:OutputFile
    abstract val testsOutputZip: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        super.init()

        jvmargs.convention(listOf("-Xmx2G"))
        sourcesOutputZip.convention(defaultOutput("$name-sources", "zip"))
        testsOutputZip.convention(defaultOutput("$name-tests", "zip"))
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

            val srcDir = serverDir.path.resolve("src/main/java")

            queue.submit(ApplySourceTransformsAction::class) {
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(apiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                accessTransforms.set(this@ApplySourceAccessTransform.accessTransforms.pathOrNull)

                inputDir.set(srcDir)

                cacheDir.set(this@ApplySourceAccessTransform.layout.cache)

                outputDir.set(srcOut)
            }

            val testSrc = serverDir.path.resolve("src/test/java")

            queue.submit(ApplySourceTransformsAction::class) {
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(apiDir.dir("src/main/java").path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                classpath.from(srcDir)
                accessTransforms.set(this@ApplySourceAccessTransform.accessTransforms.pathOrNull)

                inputDir.set(testSrc)

                cacheDir.set(this@ApplySourceAccessTransform.layout.cache)

                outputDir.set(testOut)
            }

            queue.await()

            zip(srcOut, sourcesOutputZip)
            zip(testOut, testsOutputZip)
        } finally {
            srcOut.deleteRecursively()
            testOut.deleteRecursively()
        }
    }

    abstract class ApplySourceTransformsAction : WorkAction<ApplyAccessTransformParams> {
        override fun execute() {

            val accessTransforms = parameters.accessTransforms.pathOrNull?.let { AccessTransformFormats.FML.read(it) }

            Mercury().let { mercury ->
                mercury.classPath.addAll(parameters.classpath.map { it.toPath() })

                if (accessTransforms != null) {
                    mercury.processors.clear()
                    mercury.isGracefulClasspathChecks = true
                    mercury.processors += AccessTransformerRewriter.create(accessTransforms)

                    mercury.rewrite(parameters.inputDir.path, parameters.outputDir.path)
                } else {
                    parameters.inputDir.path.copyRecursivelyTo(parameters.outputDir.path)
                }
            }
        }
    }

    interface ApplyAccessTransformParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val inputDir: DirectoryProperty
        val accessTransforms: RegularFileProperty

        val cacheDir: RegularFileProperty
        val outputDir: DirectoryProperty
    }
}