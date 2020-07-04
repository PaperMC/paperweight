/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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

import io.papermc.paperweight.PaperweightException
import org.gradle.api.Task
import org.gradle.internal.jvm.Jvm
import java.io.OutputStream

fun Task.runJar(
    jar: Any,
    workingDir: Any,
    logFile: Any?,
    jvmArgs: List<String> = listOf(),
    vararg args: String
) {
    val jarFile = project.file(jar)
    val dir = project.file(workingDir)

    val process = ProcessBuilder(
        Jvm.current().javaExecutable.canonicalPath, *jvmArgs.toTypedArray(),
        "-jar", jarFile.canonicalPath,
        *args
    ).directory(dir).start()

    val output = when {
        logFile is OutputStream -> logFile
        logFile != null -> {
            val log = project.file(logFile)
            log.outputStream().buffered()
        }
        else -> null
    }

    output.use {
        output?.let {
            redirect(process.inputStream, it)
            redirect(process.errorStream, it)
        } ?: run {
            redirect(process.inputStream, UselessOutputStream)
            redirect(process.errorStream, UselessOutputStream)
        }

        val e = process.waitFor()
        if (e != 0) {
            throw PaperweightException("Execution of $jarFile failed with exit code $e")
        }
    }
}
