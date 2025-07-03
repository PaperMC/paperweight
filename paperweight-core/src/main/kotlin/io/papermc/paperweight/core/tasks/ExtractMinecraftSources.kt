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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.deleteRecursive
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.unzip
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

// To make the Git repos cacheable they must be zipped, this tasks extracts them again...
abstract class ExtractMinecraftSources : BaseTask() {
    @get:InputFile
    abstract val zip: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val output = outputDir.path
        output.deleteRecursive()
        unzip(zip, output)
    }
}
