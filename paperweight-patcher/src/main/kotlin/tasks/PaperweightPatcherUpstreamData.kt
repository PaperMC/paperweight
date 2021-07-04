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

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.UpstreamData
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.fromJson
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.path
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.StartParameterInternal
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.build.NestedRootBuildRunner

abstract class PaperweightPatcherUpstreamData : DefaultTask() {

    @get:InputDirectory
    abstract val projectDir: DirectoryProperty

    @get:Input
    abstract val reobfPackagesToFix: ListProperty<String>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputFile
    abstract val dataFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val params = NestedRootBuildRunner.createStartParameterForNewBuild(services)
        params.projectDir = projectDir.get().asFile

        val upstreamDataFile = createTempFile(dataFile.path.parent, "data", ".json")
        try {
            params.setTaskNames(listOf(Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM))
            params.projectProperties[Constants.UPSTREAM_WORK_DIR_PROPERTY] = workDir.path.absolutePathString()
            params.projectProperties[Constants.PAPERWEIGHT_PREPARE_DOWNSTREAM] = upstreamDataFile.absolutePathString()
            params.systemPropertiesArgs[Constants.PAPERWEIGHT_DEBUG] = System.getProperty(Constants.PAPERWEIGHT_DEBUG, "false")

            NestedRootBuildRunner.runNestedRootBuild(null, params as StartParameterInternal, services)

            val upstreamData = gson.fromJson<UpstreamData>(upstreamDataFile)
            val ourData = upstreamData.copy(reobfPackagesToFix = (upstreamData.reobfPackagesToFix ?: emptyList()) + reobfPackagesToFix.get())

            dataFile.path.bufferedWriter(Charsets.UTF_8).use { writer ->
                gson.toJson(ourData, writer)
            }
        } finally {
            upstreamDataFile.deleteForcefully()
        }
    }
}
