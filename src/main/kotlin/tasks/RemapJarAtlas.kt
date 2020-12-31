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

import io.papermc.paperweight.util.AtlasHelper
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.path
import java.nio.file.Files
import javax.inject.Inject
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor

abstract class RemapJarAtlas : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val mappingsFile: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>
    @get:Input
    abstract val toNamespace: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        ensureParentExists(outputJar)
        ensureDeleted(outputJar)

        val mappings = MappingFormats.TINY.read(mappingsFile.path, fromNamespace.get(), toNamespace.get())

        val result = AtlasHelper.using(workerExecutor).remap(mappings, inputJar)
        Files.move(result, outputJar.path)
    }
}
