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

package io.papermc.paperweight.core.util

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.extension.SpigotExtension
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.*

fun Project.createBuildTasks(
    spigot: SpigotExtension,
    packagesToFix: Provider<List<String>>,
    relocatedReobfMappings: Provider<RegularFile>
): ServerArtifacts {
    val jar = tasks.named("jar", AbstractArchiveTask::class).flatMap { it.archiveFile }

    val fixJarForReobf by tasks.registering<FixJarForReobf> {
        group = "build"
        inputJar.set(jar)
        packagesToProcess.set(packagesToFix)
    }

    val includeMappings by tasks.registering<IncludeMappings> {
        group = "build"
        inputJar.set(fixJarForReobf.flatMap { it.outputJar })
        mappings.set(relocatedReobfMappings)
        mappingsDest.set("META-INF/mappings/reobf.tiny")
    }

    // We only need to manually relocate references to CB class names in string constants, the actual relocation is handled by the mappings
    val relocateConstants by tasks.registering<RelocateClassNameConstants> {
        group = "build"
        inputJar.set(includeMappings.flatMap { it.outputJar })
    }
    afterEvaluate {
        relocateConstants {
            relocate("org.bukkit.craftbukkit", "org.bukkit.craftbukkit.${spigot.packageVersion.get()}") {
                // This is not actually needed as there are no string constant references to Main
                // exclude("org.bukkit.craftbukkit.Main*")
            }
        }
    }

    val reobfJar by tasks.registering<RemapJar> {
        group = "build"
        description = "Re-obfuscate the built jar to obf mappings"

        reobfRequiresDebug()

        inputJar.set(relocateConstants.flatMap { it.outputJar })

        mappingsFile.set(relocatedReobfMappings)

        fromNamespace.set(DEOBF_NAMESPACE)
        toNamespace.set(SPIGOT_NAMESPACE)

        remapper.from(configurations.named(REMAPPER_CONFIG))
        remapperArgs.set(TinyRemapper.minecraftRemapArgs)

        outputJar.set(layout.buildDirectory.map { it.dir("libs").file("${project.name}-${project.version}-reobf.jar") })
    }

    return ServerArtifacts(
        spigot.enabled.flatMap {
            if (it) {
                includeMappings.flatMap { it.outputJar }
            } else {
                jar
            }
        },
        reobfJar.flatMap { it.outputJar }
    )
}

fun Task.reobfRequiresDebug() {
    doFirst {
        if (!paperweightDebug()) {
            throw PaperweightException(
                "Reobfuscated server jars are no longer supported and only exist for debugging purposes.\n" +
                    "If you are attempting to build a paperclip or bundler jar, use the 'mojmap' variant instead of 'reobf'.\n" +
                    "Enable paperweight debug mode to bypass this error.\n"
            )
        }
    }
}

data class ServerArtifacts(
    val mappedJar: Provider<RegularFile>,
    val reobfJar: Provider<RegularFile>,
)
