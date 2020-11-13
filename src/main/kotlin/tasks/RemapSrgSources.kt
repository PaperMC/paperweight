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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.getCsvReader
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.regex.Pattern

abstract class RemapSrgSources : ZippedTask() {

    @get:InputFile
    abstract val methodsCsv: RegularFileProperty
    @get:InputFile
    abstract val fieldsCsv: RegularFileProperty
    @get:InputFile
    abstract val paramsCsv: RegularFileProperty

    private val methods = hashMapOf<String, String>()
    private val methodDocs = hashMapOf<String, String>()
    private val fields = hashMapOf<String, String>()
    private val fieldDocs = hashMapOf<String, String>()
    private val params = hashMapOf<String, String>()

    override fun run(rootDir: File) {
        readCsv()

        rootDir.walkBottomUp()
            .filter { it.isFile && it.name.endsWith(".java") }
            .forEach(::processFile)
    }

    private fun processFile(file: File) {
        val newFile = file.resolveSibling(file.name + ".bak")
        file.bufferedReader().use { reader ->
            newFile.bufferedWriter().use { writer ->
                writeFile(reader, writer)
            }
        }

        if (!file.delete()) {
            throw PaperweightException("Failed to delete file: $file")
        }

        newFile.renameTo(file)
    }

    private fun writeFile(reader: BufferedReader, writer: BufferedWriter) {
        for (line in reader.lineSequence()) {
            replaceInLine(line, writer)
        }
    }

    private fun replaceInLine(line: String, writer: BufferedWriter) {
        val buffer = StringBuffer()
        val matcher = SRG_FINDER.matcher(line)

        while (matcher.find()) {
            val find = matcher.group()

            val result = when {
                find.startsWith("p_") -> params[find]
                find.startsWith("func_") -> methods[find]
                find.startsWith("field_") -> fields[find]
                else -> null
            } ?: matcher.group()

            matcher.appendReplacement(buffer, result)
        }

        matcher.appendTail(buffer)

        writer.appendln(buffer.toString())
    }

    private fun readCsv() {
        readCsvFile(methodsCsv.asFile.get(), methods, methodDocs)
        readCsvFile(fieldsCsv.asFile.get(), fields, fieldDocs)

        getCsvReader(paramsCsv.asFile.get()).use { reader ->
            for (line in reader.readAll()) {
                params[line[0]] = line[1]
            }
        }
    }

    private fun readCsvFile(file: File, names: MutableMap<String, String>, docs: MutableMap<String, String>) {
        getCsvReader(file).use { reader ->
            for (line in reader.readAll()) {
                names[line[0]] = line[1]
                if (line[3].isNotEmpty()) {
                    docs[line[0]] = line[3]
                }
            }
        }
    }

    companion object {
        private val SRG_FINDER = Pattern.compile("func_[0-9]+_[a-zA-Z_]+|field_[0-9]+_[a-zA-Z_]+|p_[\\w]+_\\d+_\\b")
    }
}
