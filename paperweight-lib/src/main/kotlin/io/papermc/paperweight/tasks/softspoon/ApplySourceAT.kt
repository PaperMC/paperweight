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

package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.restamp.ApplySourceATWorker
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor

// TODO: This task is only used in tests?
@CacheableTask
abstract class ApplySourceAT : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:Optional
    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val worker: WorkerExecutor

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        val queue = worker.processIsolation {
            forkOptions {
                maxHeapSize = "2G"
                executable(launcher.get().executablePath.path.absolutePathString())
            }
        }

        val classPath = minecraftClasspath.files.map { it.toPath() }.toMutableList()
        classPath.add(inputJar.convertToPath())

        queue.submit(ApplySourceATWorker::class) {
            minecraftClasspath.from(minecraftClasspath)
            atFile.set(atFile)
            inputJar.set(inputJar)
            outputJar.set(outputJar)
        }
    }
}
