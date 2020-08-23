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

package io.papermc.paperweight.util

import au.com.bytecode.opencsv.CSVParser
import au.com.bytecode.opencsv.CSVReader
import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.shared.PaperweightException
import io.papermc.paperweight.util.Constants.paperTaskOutput
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.TextMappingFormat
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Optional

val gson: Gson = Gson()

inline val Project.ext: PaperweightExtension
    get() = extensions.getByName(Constants.EXTENSION) as PaperweightExtension
inline val Project.cache: File
    get() = file(".gradle").resolve(Constants.CACHE_PATH)

fun writeMappings(format: TextMappingFormat, vararg mappings: Pair<MappingSet, File>) {
    for ((set, file) in mappings) {
        format.createWriter(file.toPath()).use { it.write(set) }
    }
}

fun redirect(input: InputStream, out: OutputStream): Thread {
    return Thread {
        try {
            input.copyTo(out)
        } catch (e: Exception) {
            throw PaperweightException("", e)
        }
    }.apply {
        isDaemon = true
        start()
    }
}

object UselessOutputStream : OutputStream() {
    override fun write(b: Int) {
    }
}

inline fun wrapException(msg: String, func: () -> Unit) {
    try {
        func()
    } catch (e: Exception) {
        throw PaperweightException(msg, e)
    }
}

fun getCsvReader(file: File) = CSVReader(
    file.reader(),
    CSVParser.DEFAULT_SEPARATOR,
    CSVParser.DEFAULT_QUOTE_CHARACTER,
    CSVParser.NULL_CHARACTER,
    1,
    false
)

fun Task.ensureParentExists(vararg files: Any) {
    for (file in files) {
        val parent = project.file(file).parentFile
        if (!parent.exists() && !parent.mkdirs()) {
            throw PaperweightException("Failed to create directory $parent")
        }
    }
}

fun Task.ensureDeleted(vararg files: Any) {
    for (file in files) {
        val f = project.file(file)
        if (f.exists() && !f.deleteRecursively()) {
            throw PaperweightException("Failed to delete file $f")
        }
    }
}

inline fun Project.toProvider(crossinline func: () -> File): Provider<RegularFile> {
    return layout.file(provider { func() })
}

fun Task.defaultOutput(name: String, ext: String): RegularFileProperty {
    return project.objects.fileProperty().convention(project.toProvider {
        project.cache.resolve(paperTaskOutput(name, ext))
    })
}
fun Task.defaultOutput(ext: String): RegularFileProperty {
    return defaultOutput(name, ext)
}
fun Task.defaultOutput(): RegularFileProperty {
    return defaultOutput("jar")
}

val <T> Optional<T>.orNull: T?
    get() = orElse(null)

val RegularFileProperty.file
    get() = get().asFile
val RegularFileProperty.fileOrNull
    get() = orNull?.asFile
val DirectoryProperty.file
    get() = get().asFile
val DirectoryProperty.fileOrNull
    get() = orNull?.asFile


private var parsedConfig: McpConfig? = null
fun mcpConfig(file: Provider<RegularFile>): McpConfig {
    if (parsedConfig != null) {
        return parsedConfig as McpConfig
    }
    parsedConfig = file.get().asFile.bufferedReader().use { reader ->
        gson.fromJson(reader)
    }
    return parsedConfig as McpConfig
}
fun mcpFile(configFile: RegularFileProperty, path: String): File {
    return configFile.file.resolveSibling(path)
}
