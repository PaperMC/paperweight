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

package io.papermc.paperweight.util

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.ext.PaperweightExtension
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.Constants.paperTaskOutput
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ThreadLocalRandom
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.MemberMapping
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider

val gson: Gson = Gson()

inline fun <reified T> Gson.fromJson(file: Any): T =
    file.convertToFile().bufferedReader().use { fromJson(it) }

val Project.ext: PaperweightExtension
    get() = extensions.getByName(Constants.EXTENSION) as PaperweightExtension
val ProjectLayout.cache: File
    get() = projectDirectory.file(".gradle/${Constants.CACHE_PATH}").asFile

@Suppress("UNCHECKED_CAST")
val Project.download: Provider<DownloadService>
    get() = gradle.sharedServices.registrations.getByName("download").service as Provider<DownloadService>

fun commentRegex(): Regex {
    return Regex("\\s*#.*")
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

fun Any.convertToFile(): File {
    return when (this) {
        is File -> this
        is Path -> this.toFile()
        is FileSystemLocation -> this.asFile
        is Provider<*> -> this.get().convertToFile()
        else -> throw PaperweightException("Unknown type representing a file: ${this.javaClass.name}")
    }
}

fun Any.convertToUrl(): URL {
    return when (this) {
        is URL -> this
        is URI -> this.toURL()
        is String -> URI.create(this).toURL()
        is Provider<*> -> this.get().convertToUrl()
        else -> throw PaperweightException("Unknown URL type: ${this.javaClass.name}")
    }
}

val File.isLibraryJar: Boolean
    get() = name.endsWith(".jar") && !name.endsWith("-sources.jar")

fun ensureParentExists(vararg files: Any) {
    for (file in files) {
        val parent = file.convertToFile().parentFile
        if (!parent.exists() && !parent.mkdirs()) {
            throw PaperweightException("Failed to create directory $parent")
        }
    }
}

fun ensureDeleted(vararg files: Any) {
    for (file in files) {
        val f = file.convertToFile()
        if (f.exists() && !f.deleteRecursively()) {
            throw PaperweightException("Failed to delete file $f")
        }
    }
}

fun BaseTask.defaultOutput(name: String, ext: String): RegularFileProperty {
    return objects.fileProperty().convention {
        layout.cache.resolve(paperTaskOutput(name, ext))
    }
}
fun BaseTask.defaultOutput(ext: String): RegularFileProperty {
    return defaultOutput(name, ext)
}
fun BaseTask.defaultOutput(): RegularFileProperty {
    return defaultOutput("jar")
}

val <T> Optional<T>.orNull: T?
    get() = orElse(null)

val RegularFileProperty.file: File
    get() = get().asFile
val RegularFileProperty.fileOrNull: File?
    get() = orNull?.asFile
val RegularFileProperty.path: Path
    get() = file.toPath()
val RegularFileProperty.pathOrNull: Path?
    get() = fileOrNull?.toPath()
val DirectoryProperty.file: File
    get() = get().asFile
val DirectoryProperty.fileOrNull: File?
    get() = orNull?.asFile
val DirectoryProperty.path: Path
    get() = file.toPath()

inline fun <reified T : Any> Project.contents(contentFile: Any, crossinline convert: (String) -> T): Provider<T> {
    return providers.fileContents(layout.projectDirectory.file(contentFile.convertToFile().absolutePath))
        .asText
        .forUseAtConfigurationTime()
        .map { convert(it) }
}

fun findOutputDir(baseFile: File): File {
    var dir: File
    do {
        dir = baseFile.resolveSibling("${baseFile.name}-" + ThreadLocalRandom.current().nextInt())
    } while (dir.exists())
    return dir
}

val MemberMapping<*, *>.parentClass: ClassMapping<*, *>
    get() = parent as ClassMapping<*, *>

private val emptyMergeResult = MergeResult(null)
fun <T> emptyMergeResult(): MergeResult<T?> {
    @Suppress("UNCHECKED_CAST")
    return emptyMergeResult as MergeResult<T?>
}

// We have to create our own task delegate because the ones Gradle provides don't work in plugin dev environments
inline fun <reified T : Task> TaskContainer.registering(noinline configure: T.() -> Unit): TaskDelegateProvider<T> {
    return TaskDelegateProvider(this, T::class, configure)
}
class TaskDelegateProvider<T : Task>(
    private val container: TaskContainer,
    private val type: KClass<T>,
    private val configure: T.() -> Unit
) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): TaskDelegate<T> {
        val provider = container.register(property.name, type.java, configure)
        return TaskDelegate(provider)
    }
}
class TaskDelegate<T : Task>(private val provider: TaskProvider<T>) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TaskProvider<T> = provider
}
