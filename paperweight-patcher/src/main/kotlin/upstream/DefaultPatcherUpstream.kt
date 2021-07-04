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

package io.papermc.paperweight.patcher.upstream

import io.papermc.paperweight.patcher.tasks.PaperweightPatcherUpstreamData
import io.papermc.paperweight.util.*
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

open class DefaultPatcherUpstream(
    private val name: String,
    protected val objects: ObjectFactory,
    protected val tasks: TaskContainer
) : PatcherUpstream {

    override val patchTasks: NamedDomainObjectContainer<PatchTaskConfig> = objects.domainObjectContainer(PatchTaskConfig::class) { name ->
        objects.newInstance<DefaultPatchTaskConfig>(name)
    }

    override val upstreamDataTaskName: String
        get() = "get${name.capitalize()}UpstreamData"
    override val upstreamDataTask: TaskProvider<PaperweightPatcherUpstreamData>
        get() = tasks.providerFor(upstreamDataTaskName)

    override val useForUpstreamData: Property<Boolean> = objects.property()

    override fun getName(): String = name
}
