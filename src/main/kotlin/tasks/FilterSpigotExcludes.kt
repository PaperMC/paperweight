/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.path
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile

/**
 * Because Spigot doesn't remap all classes, there are class and package name clashes if we don't do this in the source
 * remap step. Other than that, we don't need this jar
 */
abstract class FilterSpigotExcludes : ZippedTask() {

    @get:InputFile
    abstract val excludesFile: RegularFileProperty

    override fun run(rootDir: Path) {
        excludesFile.path.useLines { lines ->
            for (line in lines) {
                if (line.startsWith('#') || line.isBlank()) {
                    continue
                }
                val file = if (line.contains('/')) {
                    rootDir.resolve("$line.class")
                } else {
                    rootDir.resolve("net/minecraft/server/$line.class")
                }
                file.deleteForcefully()
            }
        }
    }
}
