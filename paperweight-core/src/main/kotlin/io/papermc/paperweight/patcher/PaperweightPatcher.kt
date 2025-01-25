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

package io.papermc.paperweight.patcher

import io.papermc.paperweight.core.taskcontainers.UpstreamConfigTasks
import io.papermc.paperweight.core.tasks.CheckoutRepo
import io.papermc.paperweight.core.tasks.RunNestedBuild
import io.papermc.paperweight.patcher.extension.PaperweightPatcherExtension
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.*

abstract class PaperweightPatcher : Plugin<Project> {

    override fun apply(target: Project) {
        Git.checkForGit(target.providers)
        printId<PaperweightPatcher>("paperweight-patcher", target.gradle)

        val patcher = target.extensions.create(PAPERWEIGHT_EXTENSION, PaperweightPatcherExtension::class)

        target.tasks.register<Delete>("cleanCache") {
            group = GENERAL_TASK_GROUP
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.afterEvaluate { afterEvaluate(patcher) }
    }

    private fun Project.afterEvaluate(patcher: PaperweightPatcherExtension) {
        val workDirFromProp = upstreamsDirectory()

        val applyForDownstream = tasks.register("applyForDownstream") {
            group = INTERNAL_TASK_GROUP
        }

        patcher.upstreams.forEach { upstream ->
            val checkoutTask = tasks.register<CheckoutRepo>("checkout${upstream.name.capitalized()}Repo") {
                repoName.set(upstream.name)
                url.set(upstream.repo)
                ref.set(upstream.ref)
                workDir.set(workDirFromProp)
            }

            val applyUpstream = tasks.register<RunNestedBuild>("applyUpstream") {
                projectDir.set(checkoutTask.flatMap { it.outputDir })
                tasks.add("applyForDownstream")
            }

            val upstreamConfigTasks = UpstreamConfigTasks(
                project,
                project.name,
                upstream,
                checkoutTask.flatMap { it.outputDir },
                !isBaseExecution,
                "patching",
                patcher.gitFilePatches,
                patcher.filterPatches,
                applyUpstream,
                null,
            )

            upstreamConfigTasks.setupAggregateTasks(
                upstream.name.capitalized(),
                upstream.directoryPatchSets.names.joinToString(", "),
                upstream.directoryPatchSets.names.joinToString(", ") + ", ${upstream.name} single file"
            )
            applyForDownstream { dependsOn("apply${upstream.name.capitalized()}Patches") }
            tasks.register<RunNestedBuild>("applyAllPatches") {
                group = "patching"
                val depend = "apply${upstream.name.capitalized()}Patches"
                tasks.addAll("applyAllServerPatches")
                description = "Applies all patches defined in the paperweight-patcher project and the server project. " +
                    "(equivalent to running '$depend' and then '${tasks.get().single()}' in a second Gradle invocation)"
                projectDir.set(layout.projectDirectory)
                dependsOn(depend)
            }
        }
    }
}
