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
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.eclipse.jdt.core.JavaCore
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
abstract class ApplySourceAccessTransforms : JavaLauncherTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val transforms: RegularFileProperty

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
            val testDir = serverDir.path.resolve("src/test/java")

            queue.submit(ApplySourceAccessTransformsAction::class) {
                transforms.set(this@ApplySourceAccessTransforms.transforms.pathOrNull)

                inputDir.set(srcDir)

                outputDir.set(srcOut)
            }

            queue.submit(ApplySourceAccessTransformsAction::class) {
                classpath.from(srcDir)
                transforms.set(this@ApplySourceAccessTransforms.transforms.pathOrNull)

                inputDir.set(testDir)

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

    interface ApplySourceAccessTransformsParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val inputDir: RegularFileProperty
        val transforms: RegularFileProperty

        val outputDir: RegularFileProperty
    }

    abstract class ApplySourceAccessTransformsAction : WorkAction<ApplySourceAccessTransformsParams> {
        override fun execute() {
            val transforms = parameters.transforms.pathOrNull?.let { AccessTransformFormats.FML.read(it) }

            if (transforms != null) {
                Mercury().let { mercury ->
                    mercury.sourceCompatibility = JavaCore.VERSION_17
                    mercury.isGracefulClasspathChecks = true
                    mercury.classPath.addAll(parameters.classpath.map { it.toPath() })
                    mercury.processors += AccessTransformerRewriter.create(transforms)
                    mercury.rewrite(parameters.inputDir.path, parameters.outputDir.path)
                }
            } else {
                parameters.inputDir.path.copyRecursivelyTo(parameters.outputDir.path)
            }
        }
    }
}