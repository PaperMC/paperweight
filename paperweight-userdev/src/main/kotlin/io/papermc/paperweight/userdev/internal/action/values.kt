/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
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

package io.papermc.paperweight.userdev.internal.action

import io.papermc.paperweight.util.*
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.FileCollection
import org.gradle.jvm.toolchain.JavaLauncher

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Input

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Output

interface Value<V> {
    fun get(): V

    fun bytes(): List<InputStreamProvider>
}

fun <V> value(value: V, bytes: (V) -> List<InputStreamProvider>): Value<V> = object : Value<V> {
    override fun get(): V = value

    override fun bytes(): List<InputStreamProvider> = bytes(get())

    override fun toString(): String = "value('$value')"
}

class FileCollectionValue(private val files: FileCollection) : Value<FileCollection> {
    override fun get(): FileCollection = files

    override fun bytes(): List<InputStreamProvider> = files.files
        .sortedBy { it.absolutePath }
        .flatMap {
            if (it.isDirectory) {
                InputStreamProvider.dir(it.toPath())
            } else {
                listOf(InputStreamProvider.file(it.toPath()))
            }
        }

    override fun toString(): String = "FileCollectionValue('$files')"
}

class StringValue(private val value: String) : Value<String> {
    override fun get(): String = value

    override fun bytes(): List<InputStreamProvider> = listOf(
        object : InputStreamProvider {
            override fun <T> use(op: (InputStream) -> T): T {
                return value.byteInputStream().use(op)
            }
        }
    )

    override fun toString(): String = "StringValue('$value')"
}

interface FileValue : Value<Path> {
    override fun bytes(): List<InputStreamProvider> {
        if (!get().exists()) {
            return listOf()
        }
        return listOf(InputStreamProvider.file(get()))
    }
}

interface DirectoryValue : Value<Path> {
    override fun bytes(): List<InputStreamProvider> {
        if (!get().exists()) {
            return listOf()
        }
        return InputStreamProvider.dir(get())
    }
}

fun fileValue(path: Path): FileValue = FileValueImpl(path)
fun directoryValue(path: Path): DirectoryValue = DirectoryValueImpl(path)

class ListValue<T>(private val values: List<Value<T>>) : Value<List<T>> {
    override fun get(): List<T> = values.map { it.get() }

    override fun bytes(): List<InputStreamProvider> = values.flatMap { it.bytes() }

    override fun toString(): String = "ListValue([${values.joinToString()}])"
}

fun stringListValue(values: List<String>): ListValue<String> {
    return ListValue(values.map { StringValue(it) })
}

fun fileListValue(files: List<Path>): ListValue<Path> {
    return ListValue(files.map { fileValue(it) })
}

class FileValueImpl(private val path: Path) : FileValue {
    override fun get(): Path = path

    override fun toString(): String = "FileValue('$path')"
}

class DirectoryValueImpl(private val path: Path) : DirectoryValue {
    override fun get(): Path = path

    override fun toString(): String = "DirectoryValue('$path')"
}

class LazyFileValue(val name: String) : FileValue {
    var path: Path? = null
    var owner: String? = null

    override fun get(): Path = requireNotNull(path) { "Path is not yet populated" }

    override fun toString(): String = "LazyFileValue(name='$name', owner='$owner')"
}

class LazyDirectoryValue(val name: String) : DirectoryValue {
    var path: Path? = null
    var owner: String? = null

    override fun get(): Path = requireNotNull(path) { "Path is not yet populated" }

    override fun toString(): String = "LazyDirectoryValue(name='$name', owner='$owner')"
}

fun javaLauncherValue(javaLauncher: JavaLauncher): Value<JavaLauncher> = object : Value<JavaLauncher> {
    override fun get(): JavaLauncher = javaLauncher

    override fun bytes(): List<InputStreamProvider> {
        val jdkMetadata = listOf(
            javaLauncher.metadata.javaRuntimeVersion,
            javaLauncher.metadata.jvmVersion,
            javaLauncher.metadata.vendor,
            javaLauncher.metadata.languageVersion.asInt().toString(),
        ).joinToString("\n")
        return listOf(InputStreamProvider.wrap(jdkMetadata.byteInputStream()))
    }

    override fun toString(): String = "javaLauncherValue('$javaLauncher')"
}
