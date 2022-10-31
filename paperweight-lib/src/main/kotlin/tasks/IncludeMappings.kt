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

import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

abstract class IncludeMappings : BaseTask() {
    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val mappings: RegularFileProperty

    @get:Input
    abstract val mappingsDest: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    override fun init() {
        super.init()
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    private fun addMappings() {
        outputJar.path.parent.createDirectories()
        inputJar.path.copyTo(outputJar.path, overwrite = true)
        outputJar.path.openZip().use { fs ->
            val dest = fs.getPath(mappingsDest.get())
            dest.parent.createDirectories()
            mappings.path.copyTo(dest)
        }
    }
}
