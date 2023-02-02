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
import io.papermc.paperweight.util.*
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

open class DefaultPatchTaskConfig @Inject constructor(
    private val name: String,
    private val tasks: TaskContainer,
    objects: ObjectFactory,
) : PatchTaskConfig {

    override val upstreamDirPath: Property<String> = objects.property()
    override val upstreamDir: DirectoryProperty = objects.directoryProperty()
    override val patchDir: DirectoryProperty = objects.directoryProperty()
    override val outputDir: DirectoryProperty = objects.directoryProperty()
    override val isBareDirectory: Property<Boolean> = objects.property<Boolean>().convention(false)
    override val importMcDev: Property<Boolean> = objects.property<Boolean>().convention(false)

    override val patchTaskName: String
        get() = "apply${name.capitalize()}Patches"

    override val rebuildTaskName: String
        get() = "rebuild${name.capitalize()}Patches"

    override val patchTask: TaskProvider<PatcherApplyGitPatches>
        get() = tasks.providerFor(patchTaskName)

    override val rebuildTask: TaskProvider<RebuildGitPatches>
        get() = tasks.providerFor(rebuildTaskName)

    override fun getName(): String {
        return name
    }
}
