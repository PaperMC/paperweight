/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
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

import io.papermc.paperweight.util.McpConfig
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fromJson
import io.papermc.paperweight.util.gson
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class Extract : BaseTask() {

    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    open fun run() {
        val input = inputFile.file
        val output = outputDir.file
        if (output.exists()) {
            output.deleteRecursively()
        }
        output.mkdirs()
        fs.copy {
            from(archives.zipTree(input))
            into(output)
        }
    }
}

abstract class ExtractMcp : Extract() {

    @get:OutputFile
    abstract val configFile: RegularFileProperty

    @get:OutputFile
    abstract val access: RegularFileProperty
    @get:OutputFile
    abstract val constructors: RegularFileProperty
    @get:OutputFile
    abstract val exceptions: RegularFileProperty
    @get:OutputFile
    abstract val mappings: RegularFileProperty
    @get:OutputDirectory
    abstract val patchDir: DirectoryProperty

    override fun init() {
        configFile.convention(outputDir.file("config.json"))
        access.convention(outputDir.file("config/access.txt"))
        constructors.convention(outputDir.file("config/constructors.txt"))
        exceptions.convention(outputDir.file("config/exceptions.txt"))
        mappings.convention(outputDir.file("config/joined.tsrg"))
        patchDir.convention(outputDir.dir("config/patches/server"))
    }

    @TaskAction
    override fun run() {
        super.run()

        val output = outputDir.file
        val config = gson.fromJson<McpConfig>(output.resolve("config.json"))

        // We have to know what our output file paths are at configuration time, but these could change based on the
        // config.json file.
        // So as a workaround we just rename the files the config.json file points to to our expected paths. Likely
        // is a no-op.

        output.resolve(config.data.access).renameTo(access.file.createParent())
        output.resolve(config.data.constructors).renameTo(constructors.file.createParent())
        output.resolve(config.data.exceptions).renameTo(exceptions.file.createParent())
        output.resolve(config.data.mappings).renameTo(mappings.file.createParent())
        output.resolve(config.data.patches.server).renameTo(patchDir.file.createParent())
    }

    private fun File.createParent(): File {
        val par = this.parentFile
        if (!par.exists()) {
            par.mkdirs()
        }
        return this
    }
}

abstract class ExtractMappings : Extract() {

    @get:OutputFile
    abstract val fieldsCsv: RegularFileProperty
    @get:OutputFile
    abstract val methodsCsv: RegularFileProperty
    @get:OutputFile
    abstract val paramsCsv: RegularFileProperty

    override fun init() {
        fieldsCsv.convention(outputDir.file("fields.csv"))
        methodsCsv.convention(outputDir.file("methods.csv"))
        paramsCsv.convention(outputDir.file("params.csv"))
    }
}
