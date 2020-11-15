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

import java.io.PrintWriter
import java.util.regex.Pattern
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PatchMcpCsv : DefaultTask() {

    @get:InputFile
    abstract val fieldsCsv: RegularFileProperty
    @get:InputFile
    abstract val methodsCsv: RegularFileProperty
    @get:InputFile
    abstract val paramsCsv: RegularFileProperty
    @get:InputFile
    abstract val changesFile: RegularFileProperty

    @get:OutputFile
    abstract val paperFieldCsv: RegularFileProperty
    @get:OutputFile
    abstract val paperMethodCsv: RegularFileProperty
    @get:OutputFile
    abstract val paperParamCsv: RegularFileProperty

    @TaskAction
    fun run() {
        val changes = changesFile.asFile.get().readLines()
        val changeMap = changes.asSequence()
            .filterNot { l -> l.trim().run { startsWith('#') || isEmpty() }  }
            .map { l -> commentPattern.matcher(l).replaceAll("") }
            .map { l -> l.split(',').run { get(0) to get(1) } }
            .toMap()

        val fields = fieldsCsv.asFile.get().readLines().toMutableList()
        val methods = methodsCsv.asFile.get().readLines().toMutableList()
        val params = paramsCsv.asFile.get().readLines().toMutableList()

        replaceChanges(changeMap, fields, fieldPattern)
        replaceChanges(changeMap, methods, methodPattern)
        replaceChanges(changeMap, params, paramPattern)

        paperFieldCsv.asFile.get().printWriter().use { writeFile(fields, it) }
        paperMethodCsv.asFile.get().printWriter().use { writeFile(methods, it) }
        paperParamCsv.asFile.get().printWriter().use { writeFile(params, it) }
    }

    private fun replaceChanges(changes: Map<String, String>, lines: MutableList<String>, pattern: Pattern) {
        // Start at 1 to skip csv header row
        for (i in 1 until lines.size) {
            val matcher = pattern.matcher(lines[i])
            matcher.find()
            val srgName = matcher.group(1) ?: continue
            val changedName = changes[srgName] ?: continue
            lines[i] = matcher.replaceFirst("$1,$changedName,$3")
        }
    }

    private fun writeFile(lines: List<String>, writer: PrintWriter) {
        for (line in lines) {
            writer.println(line)
        }
    }

    private companion object {
        private val fieldPattern = Pattern.compile("(field_\\d+_[a-zA-Z_]+),(\\w+),(\\d,.*)")
        private val methodPattern = Pattern.compile("(func_\\d+_[a-zA-Z_]+),(\\w+),(\\d,.*)")
        private val paramPattern = Pattern.compile("(p_i?\\d+_\\d+_),(\\w+),(\\d)")
        private val commentPattern = Pattern.compile("\\s*#.*")
    }
}
