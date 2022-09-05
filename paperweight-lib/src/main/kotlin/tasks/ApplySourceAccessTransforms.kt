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
import java.nio.file.Files
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.mercury.Mercury
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.eclipse.jdt.core.JavaCore
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class ApplySourceAccessTransforms : JavaLauncherTask() {

    @get:CompileClasspath
    abstract val vanillaJar: RegularFileProperty

    @get:CompileClasspath
    abstract val mojangMappedVanillaJar: RegularFileProperty

    @get:Optional
    @get:CompileClasspath
    abstract val vanillaRemappedSpigotJar: RegularFileProperty

    @get:CompileClasspath
    abstract val spigotDeps: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcesZip: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testsZip: RegularFileProperty

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
        val srcDir = Files.createTempDirectory(layout.cache, "sourcetransform")
        val testDir = Files.createTempDirectory(layout.cache, "testtransform")

        try {
            fs.copy {
                from(archives.zipTree(sourcesZip.path))
                into(srcDir)
            }
            fs.copy {
                from(archives.zipTree(testsZip.path))
                into(testDir)
            }

            val queue = workerExecutor.processIsolation {
                forkOptions.jvmArgs(jvmargs.get())
                forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
            }

            queue.submit(ApplyTransformsAction::class) {
                vanillaRemappedSpigotJar.pathOrNull?.let {
                    // The Spigot mapped jar is not required in some cases, such
                    // as when used by paperweight-patcher
                    classpath.from(vanillaRemappedSpigotJar.path)
                }
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                inputDir.set(srcDir)
                transforms.set(this@ApplySourceAccessTransforms.transforms.pathOrNull)
                outputDir.set(srcOut)
            }

            queue.submit(ApplyTransformsAction::class) {
                vanillaRemappedSpigotJar.pathOrNull?.let {
                    classpath.from(vanillaRemappedSpigotJar.path)
                }
                classpath.from(mojangMappedVanillaJar.path)
                classpath.from(vanillaJar.path)
                classpath.from(spigotDeps.files.filter { it.toPath().isLibraryJar })
                classpath.from(srcDir)
                inputDir.set(testDir)
                transforms.set(this@ApplySourceAccessTransforms.transforms.pathOrNull)
                outputDir.set(testOut)
            }

            queue.await()

            zip(srcOut, sourcesOutputZip)
            zip(testOut, testsOutputZip)
        } finally {
            srcDir.deleteRecursively()
            testDir.deleteRecursively()
            srcOut.deleteRecursively()
            testOut.deleteRecursively()
        }
    }

    interface ApplyTransformsParams : WorkParameters {
        val classpath: ConfigurableFileCollection
        val inputDir: RegularFileProperty
        val transforms: RegularFileProperty

        val outputDir: RegularFileProperty
    }

    abstract class ApplyTransformsAction : WorkAction<ApplyTransformsParams> {
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