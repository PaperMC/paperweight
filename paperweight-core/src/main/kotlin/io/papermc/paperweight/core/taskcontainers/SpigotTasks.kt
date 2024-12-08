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
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
open class SpigotTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
    cache: Path = project.layout.cache,
    extension: PaperweightCoreExtension = project.ext,
) : VanillaTasks(project) {

    val cloneSpigotBuildData by tasks.registering<CloneRepo> {
        url.set("https://hub.spigotmc.org/stash/scm/spigot/builddata.git")
        ref.set(project.ext.spigot.buildDataRef)
    }

    val unpackSpigotBuildData by tasks.registering<UnpackSpigotBuildData> {
        buildDataZip.set(cloneSpigotBuildData.flatMap { it.outputZip })
    }

    val buildDataInfo: Provider<BuildDataInfo> = unpackSpigotBuildData
        .flatMap { it.buildDataInfoFile }
        .map { gson.fromJson(it.path) }

    val generateSpigotMappings by tasks.registering<GenerateSpigotMappings> {
        classMappings.set(unpackSpigotBuildData.flatMap { it.classMappings })

        // todo hypo update breaks generate mappings, hardcode for now
        // sourceMappings.set(Path.of("D:\\IntellijProjects\\PaperClean\\.gradle\\caches\\paperweight\\mappings\\official-mojang+yarn.tiny"))
        sourceMappings.set(generateMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(SPIGOT_MOJANG_YARN_MAPPINGS))
        notchToSpigotMappings.set(cache.resolve(OBF_SPIGOT_MAPPINGS))
        spigotMemberMappings.set(cache.resolve(SPIGOT_MEMBER_MAPPINGS))
    }

    val spigotRemapJar by tasks.registering<SpigotRemapJar> {
        inputJar.set(filterVanillaJar.flatMap { it.outputJar })
        classMappings.set(unpackSpigotBuildData.flatMap { it.classMappings })
        accessTransformers.set(unpackSpigotBuildData.flatMap { it.atFile })

        memberMappings.set(generateSpigotMappings.flatMap { it.spigotMemberMappings })

        mcVersion.set(extension.minecraftVersion)

        // workDirName.set(extension.craftBukkit.buildDataInfo.asFile.map { it.parentFile.parentFile.name })

        specialSourceJar.set(unpackSpigotBuildData.flatMap { it.specialSourceJar })
        specialSource2Jar.set(unpackSpigotBuildData.flatMap { it.specialSource2Jar })

        classMapCommand.set(buildDataInfo.map { it.classMapCommand })
        memberMapCommand.set(buildDataInfo.map { it.memberMapCommand })
        finalMapCommand.set(buildDataInfo.map { it.finalMapCommand })
    }

    val cleanupMappings by tasks.registering<CleanupMappings> {
        sourceJar.set(spigotRemapJar.flatMap { it.outputJar })
        libraries.from(extractFromBundler.map { it.serverLibraryJars.asFileTree })
        inputMappings.set(generateSpigotMappings.flatMap { it.outputMappings })

        outputMappings.set(cache.resolve(CLEANED_SPIGOT_MOJANG_YARN_MAPPINGS))
    }

    val patchMappings by tasks.registering<PatchMappings> {
        inputMappings.set(cleanupMappings.flatMap { it.outputMappings })
        patch.set(extension.paper.mappingsPatch.fileExists(project))

        fromNamespace.set(SPIGOT_NAMESPACE)
        toNamespace.set(DEOBF_NAMESPACE)

        outputMappings.set(cache.resolve(PATCHED_SPIGOT_MOJANG_YARN_MAPPINGS))
    }

    val remapSpigotAt by tasks.registering<RemapSpigotAt> {
        inputJar.set(spigotRemapJar.flatMap { it.outputJar })
        mapping.set(patchMappings.flatMap { it.outputMappings })
        spigotAt.set(unpackSpigotBuildData.flatMap { it.atFile })
    }
}
