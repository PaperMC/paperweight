/*
 * Copyright 2018 Kyle Wood
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.papermc.paperweight.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.PrintWriter
import java.util.regex.Pattern

open class PatchMcpCsv : DefaultTask() {

    @get:InputFile lateinit var fieldsCsv: Any
    @get:InputFile lateinit var methodsCsv: Any
    @get:InputFile lateinit var paramsCsv: Any
    @get:InputFile lateinit var changesFile: Any

    @get:OutputFile lateinit var paperFieldCsv: Any
    @get:OutputFile lateinit var paperMethodCsv: Any
    @get:OutputFile lateinit var paperParamCsv: Any

    @TaskAction
    fun doStuff() {
        val changes = project.file(changesFile).readLines()
        val changeMap = changes.asSequence()
            .filterNot { l -> l.trim().run { startsWith('#') || isEmpty() }  }
            .map { l -> commentPattern.matcher(l).replaceAll("") }
            .map { l -> l.split(',').run { get(0) to get(1) } }
            .toMap()

        val fields = project.file(fieldsCsv).readLines().toMutableList()
        val methods = project.file(methodsCsv).readLines().toMutableList()
        val params = project.file(paramsCsv).readLines().toMutableList()

        replaceChanges(changeMap, fields, fieldPattern)
        replaceChanges(changeMap, methods, methodPattern)
        replaceChanges(changeMap, params, paramPattern)

        project.file(paperFieldCsv).printWriter().use { writeFile(fields, it) }
        project.file(paperMethodCsv).printWriter().use { writeFile(methods, it) }
        project.file(paperParamCsv).printWriter().use { writeFile(params, it) }
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
