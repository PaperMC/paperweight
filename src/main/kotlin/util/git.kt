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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class Git(private var repo: File) {

    init {
        if (!repo.exists()) {
            throw PaperweightException("Git directory does not exist: $repo")
        }
    }

    operator fun invoke(vararg args: String, disableGpg: Boolean = true): Command {
        val cmd = if (disableGpg) {
            arrayOf("git", "-c", "commit.gpgsign=false", *args)
        } else {
            arrayOf("git", *args)
        }
        return try {
            Command(ProcessBuilder(*cmd).directory(repo).start(), cmd.joinToString(separator = " "))
        } catch (e: IOException) {
            throw PaperweightException("Failed to execute command: ${cmd.joinToString(separator = " ")}", e)
        }
    }
}

class Command(private val process: Process, private val command: String, private val ignoreError : Boolean = false) {

    private var outStream: OutputStream = UselessOutputStream
    private var errStream: OutputStream = UselessOutputStream

    fun run(): Int {
        try {
            val input = process.inputStream
            val error = process.errorStream
            val buffer = ByteArray(1000)

            while (process.isAlive) {
                // Read both stdout and stderr on the same thread
                // This is important for how Gradle outputs the logs
                if (input.available() > 0) {
                    val count = input.read(buffer)
                    outStream.write(buffer, 0, count)
                }
                if (error.available() > 0) {
                    val count = error.read(buffer)
                    errStream.write(buffer, 0, count)
                }
                Thread.sleep(1)
            }
            // Catch any other output we may have missed
            outStream.write(input.readBytes())
            errStream.write(error.readBytes())
            return process.waitFor()
        } catch (e: Exception) {
            throw PaperweightException("Failed to call git command: $command", e)
        }
    }

    fun runSilently(silenceOut: Boolean = true, silenceErr: Boolean = false): Int {
        silence(silenceOut, silenceErr)
        return run()
    }

    fun runOut(): Int {
        setup(System.out, System.err)
        return run()
    }

    fun execute() {
        val res = run()
        if (res != 0 && !ignoreError) {
            throw PaperweightException("Command finished with $res exit code: $command")
        }
    }

    fun executeSilently(silenceOut: Boolean = true, silenceErr: Boolean = false) {
        silence(silenceOut, silenceErr)
        execute()
    }

    private fun silence(silenceOut: Boolean, silenceErr: Boolean) {
        val out = if (silenceOut) null else System.out
        val err = if (silenceErr) null else System.err
        setup(out, err)
    }

    fun executeOut() {
        setup(System.out, System.err)
        execute()
    }

    fun setup(out: OutputStream? = null, err: OutputStream? = null): Command {
        outStream = out ?: UselessOutputStream
        errStream = err ?: UselessOutputStream
        return this
    }

    fun getText(): String {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        execute()
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun readText(): String? {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        return if (run() == 0 && !ignoreError) String(out.toByteArray(), Charsets.UTF_8) else null
    }
}
