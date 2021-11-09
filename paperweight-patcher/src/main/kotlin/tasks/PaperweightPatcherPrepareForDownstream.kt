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

package io.papermc.paperweight.patcher.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "PaperweightPatcherPrepareForDownstream should always run when requested")
abstract class PaperweightPatcherPrepareForDownstream : BaseTask() {

    @get:InputFile
    abstract val upstreamDataFile: RegularFileProperty

    @get:Input
    abstract val reobfPackagesToFix: ListProperty<String>

    @get:InputFile
    abstract val reobfMappingsPatch: RegularFileProperty

    @get:OutputFile
    abstract val dataFile: RegularFileProperty

    @TaskAction
    fun run() {
        val upstreamData = readUpstreamData(upstreamDataFile)

        val ourData = upstreamData.copy(
            reobfPackagesToFix = reobfPackagesToFix.get(),
            reobfMappingsPatch = reobfMappingsPatch.path,
        )

        dataFile.path.parent.createDirectories()
        dataFile.path.bufferedWriter().use { writer ->
            gson.toJson(ourData, writer)
        }
    }
}
