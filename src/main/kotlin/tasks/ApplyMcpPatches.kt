/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.mcpConfig
import io.papermc.paperweight.util.mcpFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import java.io.File

open class ApplyMcpPatches : ZippedTask() {

    @InputFile
    val configFile: RegularFileProperty = project.objects.fileProperty()

    init {
        inputs.dir(configFile.map { mcpConfig(configFile) }.map { mcpFile(configFile, it.data.patches.server) })
    }

    override fun run(rootDir: File) {
        val config = mcpConfig(configFile)
        val serverPatchDir = mcpFile(configFile, config.data.patches.server)

        val git = Git(rootDir)

        val extension = ".java.patch"
        project.fileTree(serverPatchDir).matching {
            include("*$extension")
        }.forEach { patch ->
            val patchName = patch.name
            print("Patching ${patchName.substring(0, extension.length)}")
            val exit = git("apply", "--ignore-whitespace", patch.absolutePath).setup(System.out, null).run()
            if (exit != 0) {
                println("...Failed")
            } else {
                println()
            }
        }
    }
}
