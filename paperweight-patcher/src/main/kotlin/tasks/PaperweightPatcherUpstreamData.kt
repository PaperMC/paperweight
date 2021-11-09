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
import io.papermc.paperweight.util.constants.*
import kotlin.collections.set
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.StartParameterInternal
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.internal.build.NestedRootBuildRunner

@UntrackedTask(because = "Nested build does it's own up-to-date checking")
abstract class PaperweightPatcherUpstreamData : BaseTask() {

    @get:InputDirectory
    abstract val projectDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputFile
    abstract val dataFile: RegularFileProperty

    @TaskAction
    fun run() {
        val params = NestedRootBuildRunner.createStartParameterForNewBuild(services)
        params.projectDir = projectDir.get().asFile

        val upstreamDataFile = dataFile.path
        upstreamDataFile.deleteForcefully() // We won't be the ones to create this file

        params.setTaskNames(listOf(PAPERWEIGHT_PREPARE_DOWNSTREAM))

        params.projectProperties[UPSTREAM_WORK_DIR_PROPERTY] = workDir.path.absolutePathString()
        params.projectProperties[PAPERWEIGHT_DOWNSTREAM_FILE_PROPERTY] = upstreamDataFile.absolutePathString()

        params.systemPropertiesArgs[PAPERWEIGHT_DEBUG] = System.getProperty(PAPERWEIGHT_DEBUG, "false")

        NestedRootBuildRunner.runNestedRootBuild(null, params as StartParameterInternal, services)
    }
}
