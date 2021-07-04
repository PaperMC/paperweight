/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

import io.papermc.paperweight.PaperweightException
import java.io.OutputStream
import java.util.jar.JarFile
import kotlin.io.path.*
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLauncher

fun JavaLauncher.runJar(
    classpath: FileCollection,
    workingDir: Any,
    logFile: Any?,
    jvmArgs: List<String> = listOf(),
    vararg args: String
) {
    var mainClass: String? = null
    for (file in classpath.files) {
        mainClass = JarFile(file).use { jarFile ->
            jarFile.manifest.mainAttributes.getValue("Main-Class")
        } ?: continue
        break
    }
    if (mainClass == null) {
        throw PaperweightException("Could not determine main class name for ${classpath.asPath}")
    }

    val dir = workingDir.convertToPath()

    val output = when {
        logFile is OutputStream -> logFile
        logFile != null -> {
            val log = logFile.convertToPath()
            log.parent.createDirectories()
            log.outputStream().buffered()
        }
        else -> UselessOutputStream
    }

    val processBuilder = ProcessBuilder(
        this.executablePath.path.absolutePathString(),
        *jvmArgs.toTypedArray(),
        "-classpath",
        classpath.asPath,
        mainClass,
        *args
    ).directory(dir)

    output.writer().let {
        it.appendLine("Command: ${processBuilder.command().joinToString(" ")}")
        it.flush()
    }

    val process = processBuilder.start()

    output.use {
        redirect(process.inputStream, it)
        redirect(process.errorStream, it)

        val e = process.waitFor()
        if (e != 0) {
            throw PaperweightException("Execution of ${classpath.asPath} failed with exit code $e")
        }
    }
}

fun Provider<JavaLauncher>.runJar(
    classpath: FileCollection,
    workingDir: Any,
    logFile: Any?,
    jvmArgs: List<String> = listOf(),
    vararg args: String
) {
    get().runJar(classpath, workingDir, logFile, jvmArgs, *args)
}
