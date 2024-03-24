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

package io.papermc.paperweight.tasks.mache

import io.papermc.paperweight.util.*
import javax.inject.Inject
import kotlin.io.path.name
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class RemapJar : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val logMissingLvtSuggestions: Property<Boolean>

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverJar: RegularFileProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val serverMappings: RegularFileProperty

    @get:Classpath
    abstract val codebookClasspath: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val remapperClasspath: ConfigurableFileCollection

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFiles
    abstract val paramMappings: ConfigurableFileCollection

    @get:Classpath
    abstract val constants: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Inject
    abstract val worker: WorkerExecutor

    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun run() {
        val out = outputJar.convertToPath().ensureClean()

        val queue = worker.processIsolation {
            classpath.from(codebookClasspath)
            forkOptions {
                maxHeapSize = "2G"
            }
        }

        val logFile = out.resolveSibling("${out.name}.log")

        queue.submit(RunCodeBookWorker::class) {
            tempDir.set(layout.buildDirectory.dir(".tmp_codebook"))
            serverJar.set(this@RemapJar.serverJar)
            classpath.from(this@RemapJar.minecraftClasspath)
            remapperClasspath.from(this@RemapJar.remapperClasspath)
            serverMappings.set(this@RemapJar.serverMappings)
            paramMappings.from(this@RemapJar.paramMappings)
            constants.from(this@RemapJar.constants)
            outputJar.set(this@RemapJar.outputJar)
            logs.set(logFile.toFile())
            logMissingLvtSuggestions.set(this@RemapJar.logMissingLvtSuggestions)
        }
    }
}
