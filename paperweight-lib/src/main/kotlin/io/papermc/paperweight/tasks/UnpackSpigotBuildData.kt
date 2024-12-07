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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class UnpackSpigotBuildData : BaseTask() {
    @get:InputFile
    abstract val buildDataZip: RegularFileProperty

    @get:OutputFile
    abstract val buildDataInfoFile: RegularFileProperty

    @get:OutputFile
    abstract val excludesFile: RegularFileProperty

    @get:OutputFile
    abstract val atFile: RegularFileProperty

    @get:OutputFile
    abstract val classMappings: RegularFileProperty

    @get:OutputFile
    abstract val specialSourceJar: RegularFileProperty

    @get:OutputFile
    abstract val specialSource2Jar: RegularFileProperty

    override fun init() {
        buildDataInfoFile.convention(defaultOutput("spigot-build-data-info", "json"))
        excludesFile.convention(defaultOutput("spigot-excludes", "exclude"))
        atFile.convention(defaultOutput("spigot-ats", "at"))
        classMappings.convention(defaultOutput("spigot-class-mapping", "csrg"))
        specialSourceJar.convention(defaultOutput("special-source", "jar"))
        specialSource2Jar.convention(defaultOutput("special-source-2", "jar"))
    }

    @TaskAction
    fun run() {
        buildDataZip.path.openZip().use {
            val root = it.getPath("/")
            root.resolve("info.json")
                .copyTo(buildDataInfoFile.path.createParentDirectories(), overwrite = true)
            val mappings = root.resolve("mappings")
            bukkitFileFrom(mappings, "exclude")
                .copyTo(excludesFile.path.createParentDirectories(), overwrite = true)
            bukkitFileFrom(mappings, "at")
                .copyTo(atFile.path.createParentDirectories(), overwrite = true)
            bukkitFileFrom(mappings, "csrg")
                .copyTo(classMappings.path.createParentDirectories(), overwrite = true)
            root.resolve("bin/SpecialSource.jar")
                .copyTo(specialSourceJar.path.createParentDirectories(), overwrite = true)
            root.resolve("bin/SpecialSource-2.jar")
                .copyTo(specialSource2Jar.path.createParentDirectories(), overwrite = true)
        }
    }

    private fun bukkitFileFrom(dir: Path, extension: String): Path =
        dir.useDirectoryEntries { it.filter { f -> f.name.endsWith(extension) }.single() }
}
