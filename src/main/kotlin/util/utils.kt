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

package io.papermc.paperweight.util

import au.com.bytecode.opencsv.CSVParser
import au.com.bytecode.opencsv.CSVReader
import com.google.gson.Gson
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.ext.PaperweightExtension
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.TextMappingFormat
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ThreadLocalRandom

internal val gson = Gson()

internal inline val Project.ext
    get() = extensions.getByName(Constants.EXTENSION) as PaperweightExtension
internal inline val Project.cache
    get() = gradle.gradleUserHomeDir.resolve(Constants.CACHE_PATH)

internal inline fun <reified T : Task> TaskContainer.register(name: String, noinline action: T.() -> Unit): TaskProvider<T> =
    register(name, T::class.java) { action() }

internal fun writeMappings(format: TextMappingFormat, vararg mappings: Pair<MappingSet, File>) {
    for ((set, file) in mappings) {
        file.bufferedWriter().use { stream ->
            format.createWriter(stream).write(set)
        }
    }
}

internal operator fun <T : Task> TaskProvider<T>.invoke(func: (T) -> Unit) {
    this.configure {
        func(this)
    }
}

internal fun redirect(input: InputStream, out: OutputStream) {
    Thread {
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

internal object UselessOutputStream : OutputStream() {
    override fun write(b: Int) {
    }
}

internal inline fun wrapException(msg: String, func: () -> Unit) {
    try {
        func()
    } catch (e: Exception) {
        throw PaperweightException(msg, e)
    }
}

internal fun getReader(file: File) = CSVReader(
    file.reader(),
    CSVParser.DEFAULT_SEPARATOR,
    CSVParser.DEFAULT_QUOTE_CHARACTER,
    CSVParser.NULL_CHARACTER,
    1,
    false
)

internal operator fun Appendable.plusAssign(text: String) {
    append(text)
}

internal fun Task.ensureParentExists(vararg files: Any) {
    for (file in files) {
        val parent = project.file(file).parentFile
        if (!parent.exists() && !parent.mkdirs()) {
            throw PaperweightException("Failed to create directory $parent")
        }
    }
}

internal fun Task.ensureDeleted(vararg files: Any) {
    for (file in files) {
        val f = project.file(file)
        if (f.exists() && !f.deleteRecursively()) {
            throw PaperweightException("Failed to delete file $f")
        }
    }
}
