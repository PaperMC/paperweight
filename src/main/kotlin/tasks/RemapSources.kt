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
import org.cadixdev.mercury.at.AccessTransformerRewriter
import org.cadixdev.mercury.extra.AccessAnalyzerProcessor
import org.cadixdev.mercury.remapper.MercuryRemapper
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
}
