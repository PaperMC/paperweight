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

package io.papermc.paperweight.patcher.upstream

import io.papermc.paperweight.patcher.tasks.PatcherApplyGitPatches
import io.papermc.paperweight.tasks.*
import org.gradle.api.Named
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider

interface PatchTaskConfig : Named {

    val upstreamDirPath: Property<String>
    val upstreamDir: DirectoryProperty

    val patchDir: DirectoryProperty
    val outputDir: DirectoryProperty

    val isBareDirectory: Property<Boolean>
    val importMcDev: Property<Boolean>

    val patchTaskName: String
    val rebuildTaskName: String
    val patchTask: TaskProvider<PatcherApplyGitPatches>
    val rebuildTask: TaskProvider<RebuildGitPatches>
}
