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

import io.papermc.paperweight.PaperweightException
import java.io.OutputStream
import org.gradle.internal.jvm.Jvm

fun runJar(jar: Any, workingDir: Any, logFile: Any?, jvmArgs: List<String> = listOf(), vararg args: String) {
    val jarFile = jar.convertToFile()
    val dir = workingDir.convertToFile()

    val output = when {
        logFile is OutputStream -> logFile
        logFile != null -> {
            val log = logFile.convertToFile()
            log.outputStream().buffered()
        }
        else -> UselessOutputStream
    }

    val processBuilder = ProcessBuilder(
        Jvm.current().javaExecutable.canonicalPath,
        *jvmArgs.toTypedArray(),
        "-jar",
        jarFile.canonicalPath,
        *args
    ).directory(dir)

    output.writer().let {
        it.appendln("Command: ${processBuilder.command().joinToString(" ")}")
        it.flush()
    }

    val process = processBuilder.start()

    output.use {
        redirect(process.inputStream, it)
        redirect(process.errorStream, it)

        val e = process.waitFor()
        if (e != 0) {
            throw PaperweightException("Execution of $jarFile failed with exit code $e")
        }
    }
}
