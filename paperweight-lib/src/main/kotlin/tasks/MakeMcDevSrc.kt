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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import java.nio.file.StandardCopyOption
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class MakeMcDevSrc : DefaultTask() {

    @get:Classpath
    abstract val source: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val paperServerDir: DirectoryProperty

    @get:OutputDirectory
    abstract val target: DirectoryProperty

    @TaskAction
    fun run() {
        val paperSource = paperServerDir.path.resolve("src/main/java")

        ensureDeleted(target)

        source.path.openZip().use { fs ->
            fs.walk().use { stream ->
                stream.forEach { p ->
                    val paperFile = paperSource.resolve(p.absolutePathString().substring(1))
                    if (!paperFile.isRegularFile()) {
                        val targetFile = target.path.resolve(p.absolutePathString().substring(1))
                        targetFile.parent.createDirectories()
                        p.copyTo(targetFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }
}
