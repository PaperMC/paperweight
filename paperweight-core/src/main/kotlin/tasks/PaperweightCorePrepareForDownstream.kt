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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.UpstreamData
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PaperweightCorePrepareForDownstream : DefaultTask() {

    @get:InputFile
    abstract val vanillaJar: RegularFileProperty

    @get:InputFile
    abstract val remappedJar: RegularFileProperty

    @get:InputFile
    abstract val decompiledJar: RegularFileProperty

    @get:Input
    abstract val mcVersion: Property<String>

    @get:InputDirectory
    abstract val mcLibrariesDir: DirectoryProperty

    @get:InputDirectory
    abstract val mcLibrariesSourcesDir: DirectoryProperty

    @get:InputDirectory
    abstract val spigotLibrariesSourcesDir: DirectoryProperty

    @get:Input
    abstract val vanillaJarIncludes: ListProperty<String>

    @get:InputFile
    abstract val mcLibrariesFile: RegularFileProperty

    @get:InputFile
    abstract val mappings: RegularFileProperty

    @get:InputFile
    abstract val notchToSpigotMappings: RegularFileProperty

    @get:InputFile
    abstract val sourceMappings: RegularFileProperty

    @get:Input
    abstract val reobfPackagesToFix: ListProperty<String>

    @get:InputFile
    abstract val reobfMappingsPatch: RegularFileProperty

    @get:OutputFile
    abstract val dataFile: RegularFileProperty

    @get:Inject
    abstract val providers: ProviderFactory

    @get:Input
    abstract val paramMappingsUrl: Property<String>

    @get:Classpath
    abstract val paramMappingsConfig: Property<Configuration>

    @get:InputFile
    abstract val atFile: RegularFileProperty

    @get:InputFile
    abstract val spigotRecompiledClasses: RegularFileProperty

    @get:InputFile
    abstract val bundlerVersionJson: RegularFileProperty

    @get:InputFile
    abstract val serverLibrariesTxt: RegularFileProperty

    @get:InputFile
    abstract val serverLibrariesList: RegularFileProperty

    @TaskAction
    fun run() {
        val dataFilePath = dataFile.path

        dataFilePath.parent.createDirectories()

        val data = UpstreamData(
            vanillaJar.path,
            remappedJar.path,
            decompiledJar.path,
            mcVersion.get(),
            mcLibrariesDir.path,
            mcLibrariesSourcesDir.path,
            mcLibrariesFile.path,
            spigotLibrariesSourcesDir.path,
            mappings.path,
            notchToSpigotMappings.path,
            sourceMappings.path,
            reobfPackagesToFix.get(),
            reobfMappingsPatch.path,
            vanillaJarIncludes.get(),
            determineMavenDep(paramMappingsUrl, paramMappingsConfig),
            atFile.path,
            spigotRecompiledClasses.path,
            bundlerVersionJson.path,
            serverLibrariesTxt.path,
            serverLibrariesList.path
        )
        dataFilePath.bufferedWriter(Charsets.UTF_8).use { writer ->
            gson.toJson(data, writer)
        }
    }
}
