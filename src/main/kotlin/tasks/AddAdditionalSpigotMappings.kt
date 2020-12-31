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

import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.fileOrNull
import java.io.File
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class AddAdditionalSpigotMappings : BaseTask() {

    @get:InputFile
    abstract val classSrg: RegularFileProperty
    @get:InputFile
    abstract val memberSrg: RegularFileProperty
    @get:Optional
    @get:InputFile
    abstract val additionalClassEntriesSrg: RegularFileProperty
    @get:Optional
    @get:InputFile
    abstract val additionalMemberEntriesSrg: RegularFileProperty

    @get:OutputFile
    abstract val outputClassSrg: RegularFileProperty
    @get:OutputFile
    abstract val outputMemberSrg: RegularFileProperty

    override fun init() {
        outputClassSrg.convention(defaultOutput("class.csrg"))
        outputMemberSrg.convention(defaultOutput("member.csrg"))
    }

    @TaskAction
    fun run() {
        addLines(classSrg.file, additionalClassEntriesSrg.fileOrNull, outputClassSrg.file)
        addLines(memberSrg.file, additionalMemberEntriesSrg.fileOrNull, outputMemberSrg.file)
    }

    private fun addLines(inFile: File, appendFile: File?, outputFile: File) {
        val lines = mutableListOf<String>()
        inFile.useLines { seq ->
            seq.filter { it.startsWith("") }
        }
        inFile.forEachLine { line -> lines += line }
        appendFile?.useLines { seq -> seq.forEach { lines.add(it) } }
        lines.sort()
        outputFile.bufferedWriter().use { writer ->
            lines.forEach { writer.appendln(it) }
        }
    }
}
