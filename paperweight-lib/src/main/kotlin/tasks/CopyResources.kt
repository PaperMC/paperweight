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

import io.papermc.paperweight.util.deleteRecursively
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.zip
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CopyResources : BaseTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:Classpath
    abstract val vanillaJar: RegularFileProperty

    @get:Input
    abstract val includes: ListProperty<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputJar.path
        val target = out.resolveSibling("${out.name}.dir")
        target.createDirectories()

        fs.copy {
            from(archives.zipTree(vanillaJar)) {
                for (inc in this@CopyResources.includes.get()) {
                    include(inc)
                }
            }
            into(target)
            from(archives.zipTree(inputJar))
            into(target)
        }

        zip(target, outputJar)
        target.deleteRecursively()
    }
}
