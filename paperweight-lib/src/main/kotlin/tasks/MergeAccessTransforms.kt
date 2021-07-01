/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.pathOrNull
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class MergeAccessTransforms : BaseTask() {

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val firstFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val secondFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        outputFile.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        val ats = sequenceOf(firstFile.pathOrNull, secondFile.pathOrNull)
            .filterNotNull()
            .filter { it.exists() }
            .map { AccessTransformFormats.FML.read(it) }

        val outputAt = AccessTransformSet.create()
        for (at in ats) {
            outputAt.merge(at)
        }

        AccessTransformFormats.FML.write(outputFile.path, outputAt)
    }
}
