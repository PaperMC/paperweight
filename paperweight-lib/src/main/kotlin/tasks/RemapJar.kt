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
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class RemapJar : JavaLauncherTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingsFile: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    @get:Input
    abstract val rebuildSourceFilenames: Property<Boolean>

    @get:CompileClasspath
    abstract val remapClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val remapper: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val jvmargs: ListProperty<String>

    @get:Internal
    abstract val singleThreaded: Property<Boolean>

    override fun init() {
        super.init()

        outputJar.convention(defaultOutput())
        singleThreaded.convention(true)
        jvmargs.convention(listOf("-Xmx1G"))
        rebuildSourceFilenames.convention(true)
    }

    @TaskAction
    fun run() {
        val logFile = layout.cache.resolve(paperTaskOutput("log"))
        ensureDeleted(logFile)

        val args = mutableListOf(
            inputJar.path.absolutePathString(),
            outputJar.path.absolutePathString(),
            mappingsFile.path.absolutePathString(),
            fromNamespace.get(),
            toNamespace.get(),
            *remapClasspath.asFileTree.map { it.absolutePath }.toTypedArray(),
            "--fixpackageaccess",
            "--renameinvalidlocals"
        )
        if (singleThreaded.get()) {
            args += "--threads=1"
        }
        if (rebuildSourceFilenames.get()) {
            args += "--rebuildsourcefilenames"
        }

        ensureParentExists(logFile)
        launcher.runJar(remapper, layout.cache, logFile, jvmArgs = jvmargs.get(), args = args.toTypedArray())
    }
}
