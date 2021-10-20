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

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class VanillaTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
) : GeneralTasks(project) {

    val minecraftLibraries = project.downloadMinecraftLibraries(minecraftLibrariesList)

    val inspectVanillaJar by tasks.registering<InspectVanillaJar> {
        inputJar.set(serverJar)
        libraries.from(minecraftLibraries)
        mcLibraries.set(minecraftLibrariesList)

        serverLibraries.set(cache.resolve(SERVER_LIBRARIES))
    }

    val minecraftLibrariesSources = inspectVanillaJar.flatMap {
        project.downloadMinecraftLibrariesSources(it.serverLibraries.map { libs -> libs.path.readLines() })
    }

    val generateMappings by tasks.registering<GenerateMappings> {
        vanillaJar.set(filterVanillaJar.flatMap { it.outputJar })
        libraries.from(minecraftLibraries)

        vanillaMappings.set(serverMappings)
        paramMappings.fileProvider(project.configurations.named(PARAM_MAPPINGS_CONFIG).map { it.singleFile })

        outputMappings.set(cache.resolve(MOJANG_YARN_MAPPINGS))
    }

    val remapJar by tasks.registering<RemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        mappingsFile.set(generateMappings.flatMap { it.outputMappings })
        fromNamespace.set(OBF_NAMESPACE)
        toNamespace.set(DEOBF_NAMESPACE)
        remapper.from(project.configurations.named(REMAPPER_CONFIG))
    }

    val fixJar by tasks.registering<FixJar> {
        inputJar.set(remapJar.flatMap { it.outputJar })
        vanillaJar.set(serverJar)
    }
}
