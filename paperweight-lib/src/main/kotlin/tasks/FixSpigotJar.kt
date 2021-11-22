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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.tree.ClassNode

@CacheableTask
abstract class FixSpigotJar : JavaLauncherTask() {
    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:Internal
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        super.init()

        jvmArgs.convention(listOf("-Xmx512m"))
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        ensureParentExists(outputJar.path)
        ensureDeleted(outputJar.path)

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs(jvmArgs.get())
            forkOptions.executable(launcher.get().executablePath.path.absolutePathString())
        }

        queue.submit(Action::class) {
            inputJar.set(this@FixSpigotJar.inputJar.path)
            outputJar.set(this@FixSpigotJar.outputJar.path)
        }
    }

    abstract class Action : WorkAction<Action.Params> {
        interface Params : WorkParameters {
            val inputJar: RegularFileProperty
            val outputJar: RegularFileProperty
        }

        override fun execute() {
            parameters.outputJar.path.writeZip().use { out ->
                parameters.inputJar.path.openZip().use { input ->
                    FixJar.processJars(input, out, FixSpigotJarClassProcessor)
                }
            }
        }

        private object FixSpigotJarClassProcessor : FixJar.ClassProcessor {
            override fun processClass(node: ClassNode, classNodeCache: ClassNodeCache) {
                SpongeRecordFixer.fix(node, classNodeCache, true, true)
            }
        }
    }
}
