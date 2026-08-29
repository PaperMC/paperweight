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

import io.papermc.paperweight.core.tasks.patchroulette.PatchRouletteTasks
import io.papermc.paperweight.tasks.*
import kotlin.test.Test
import kotlin.test.assertEquals
import org.gradle.api.Task
import org.gradle.api.internal.TaskInternal

class GitMutationLockRegistrationTest : TaskTest() {
    private fun Task.requiredServicesCount(): Int = (this as TaskInternal).requiredServices.elements.size

    private fun setupPatchingTasks(readOnly: Boolean): Pair<PatchingTasks, List<Task>> {
        val project = setupProject()
        val patchingTasks = PatchingTasks(
            project = project,
            forkName = "testFork",
            patchSetName = "testPatchSet",
            taskGroup = "patching",
            readOnly = readOnly,
            filePatchDir = project.objects.directoryProperty().convention(project.layout.projectDirectory.dir("patches/files")),
            rejectsDir = project.objects.directoryProperty().convention(project.layout.projectDirectory.dir("patches/rejected")),
            featurePatchDir = project.objects.directoryProperty().convention(project.layout.projectDirectory.dir("patches/features")),
            baseDir = project.provider { project.layout.projectDirectory.dir("base") },
            gitFilePatches = project.provider { false },
            filterPatches = project.provider { true },
            outputDir = project.objects.directoryProperty().convention(project.layout.projectDirectory.dir("worktree")),
        )
        val tasks = buildList {
            add(patchingTasks.applyFilePatches.get())
            add(patchingTasks.applyFilePatchesFuzzy.get())
            add(patchingTasks.applyFeaturePatches.get())
            if (!readOnly) {
                add(project.tasks.named(patchingTasks.rebuildFilePatchesName).get())
                add(project.tasks.named(patchingTasks.fixupFilePatchesName).get())
                add(project.tasks.named(patchingTasks.rebuildFeaturePatchesName).get())
                add(project.tasks.named("applyOrMoveTestPatchSetFilePatches").get())
            }
        }
        return patchingTasks to tasks
    }

    @Test
    fun `writable patching tasks require git mutation lock service`() {
        val (_, tasks) = setupPatchingTasks(readOnly = false)

        tasks.forEach { task ->
            assertEquals(
                expected = 1,
                actual = task.requiredServicesCount(),
                message = "Expected ${task.path} to require the git mutation lock service",
            )
        }
    }

    @Test
    fun `read only patching tasks do not require git mutation lock service`() {
        val (_, tasks) = setupPatchingTasks(readOnly = true)

        tasks.forEach { task ->
            assertEquals(
                expected = 0,
                actual = task.requiredServicesCount(),
                message = "Expected ${task.path} to avoid the git mutation lock service",
            )
        }
    }

    @Test
    fun `patch roulette only locks apply task`() {
        val project = setupProject()
        PatchRouletteTasks(
            target = project,
            namePrefix = "paper",
            minecraftVer = project.provider { "26.1.2" },
            patchDirectory = project.provider { project.layout.projectDirectory.dir("patches") },
            targetDirectory = project.layout.projectDirectory.dir("target"),
        )

        val apply = project.tasks.named("paperPatchRouletteApply").get()
        val cancel = project.tasks.named("paperPatchRouletteCancel").get()
        val finish = project.tasks.named("paperPatchRouletteFinish").get()

        assertEquals(expected = 1, actual = apply.requiredServicesCount())
        assertEquals(expected = 0, actual = cancel.requiredServicesCount())
        assertEquals(expected = 0, actual = finish.requiredServicesCount())
    }
}
