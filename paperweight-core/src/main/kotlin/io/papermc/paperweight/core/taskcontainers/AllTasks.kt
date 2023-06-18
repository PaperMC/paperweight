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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.core.extension.PaperweightCoreExtension
import io.papermc.paperweight.tasks.ApplyFilePatches
import io.papermc.paperweight.tasks.ApplyGitPatches
import io.papermc.paperweight.tasks.RebuildFilePatches
import io.papermc.paperweight.tasks.RebuildGitPatches
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.constants.BASE_PROJECT
import io.papermc.paperweight.util.isBaseExecution
import io.papermc.paperweight.util.registering
import io.papermc.paperweight.util.set
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.provideDelegate
import java.nio.file.Path

@Suppress("MemberVisibilityCanBePrivate")
open class AllTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
): McpConfigTasks(project) {

    val applyFilePatches by tasks.registering<ApplyFilePatches> {
        group = "paper"
        description = "Applies file patches"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }
        printOutput.set(project.isBaseExecution)

        patchFolder.set(extension.paper.filePatchesDir)
        ignorePattern.set(extension.paper.ignoreProperty)
        // TODO temp, to speed stuff up
        //vanillaBase.set(prepareBase.flatMap { it.output })
        vanillaBase.set(cache.resolve(BASE_PROJECT))
        outputDir.set(extension.paper.paperServerDir)
    }

    val applyFeaturePatches by tasks.registering<ApplyGitPatches> {
        group = "paper"
        description = "Applies feature patches"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }

        dependsOn(applyFilePatches)
        // TODO
    }

    @Suppress("unused")
    val applyPatches by tasks.registering<Task> {
        group = "paper"
        description = "Applies all Paper patches"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }

        dependsOn(applyFeaturePatches)
    }

    val rebuildFilePatches by tasks.registering<RebuildFilePatches> {
        group = "paper"
        description = "Rebuild file patches"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }

        patchFolder.set(extension.paper.filePatchesDir)
        ignorePattern.set(extension.paper.ignoreProperty)
        // TODO temp, to speed stuff up
        //vanillaBase.set(prepareBase.flatMap { it.output })
        vanillaBase.set(cache.resolve(BASE_PROJECT))
        serverDir.set(extension.paper.paperServerDir)
    }

    val rebuildFeaturePatches by tasks.registering<RebuildGitPatches> {
        group = "paper"
        description = "Rebuilds feature patches"
        inputDir.set(extension.paper.paperApiDir)
        baseRef.set("base")

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }

        // TODO
        patchDir.set(extension.paper.featurePatchesDir)

        dependsOn(rebuildFilePatches)
    }

    @Suppress("unused")
    val rebuildPatches by tasks.registering<Task> {
        group = "paper"
        description = "Rebuilds all Paper patches"

        if (project.isBaseExecution) {
            doNotTrackState("$name should always run when requested as part of the base execution.")
        }

        dependsOn(rebuildFeaturePatches)
    }
}
