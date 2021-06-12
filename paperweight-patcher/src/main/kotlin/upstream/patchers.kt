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

package io.papermc.paperweight.patcher.upstream

import io.papermc.paperweight.patcher.tasks.CheckoutRepo
import io.papermc.paperweight.util.providerFor
import org.gradle.api.Action
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

open class DefaultRepoPatcherUpstream(
    name: String,
    objects: ObjectFactory,
    tasks: TaskContainer,
    protected val layout: ProjectLayout
) : DefaultPatcherUpstream(name, objects, tasks), RepoPatcherUpstream {

    override val url: Property<String> = objects.property()
    override val ref: Property<String> = objects.property()

    override val cloneTaskName: String
        get() = "clone${name.capitalize()}Repo"
    override val cloneTask: TaskProvider<CheckoutRepo>
        get() = tasks.providerFor(cloneTaskName)

    override fun withStandardPatcher(action: Action<StandardPatcherConfig>) {
        val config = objects.newInstance(StandardPatcherConfig::class)
        config.apiPatchDir.convention(layout.projectDirectory.dir("patches/api"))
        config.serverPatchDir.convention(layout.projectDirectory.dir("patches/server"))
        action.execute(config)

        patchTasks {
            register("api") {
                sourceDirPath.set(config.apiSourceDirPath)
                patchDir.set(config.apiPatchDir)
                outputDir.set(config.apiOutputDir)
            }

            register("server") {
                sourceDirPath.set(config.serverSourceDirPath)
                patchDir.set(config.serverPatchDir)
                outputDir.set(config.serverOutputDir)
            }
        }
    }
}

open class DefaultPaperRepoPatcherUpstream(name: String, objects: ObjectFactory, taskContainer: TaskContainer, layout: ProjectLayout) :
    DefaultRepoPatcherUpstream(name, objects, taskContainer, layout), PaperRepoPatcherUpstream {

    override fun withPaperPatcher(action: Action<MinimalPatcherConfig>) {
        val minimalConfig = objects.newInstance(MinimalPatcherConfig::class)
        minimalConfig.apiPatchDir.convention(layout.projectDirectory.dir("patches/api"))
        minimalConfig.serverPatchDir.convention(layout.projectDirectory.dir("patches/server"))
        action.execute(minimalConfig)

        val paperAction = Action<StandardPatcherConfig> {
            baseName("Paper")

            apiPatchDir.set(minimalConfig.apiPatchDir)
            apiOutputDir.set(minimalConfig.apiOutputDir)

            serverPatchDir.set(minimalConfig.serverPatchDir)
            serverOutputDir.set(minimalConfig.serverOutputDir)
        }

        withStandardPatcher(paperAction)
    }
}
