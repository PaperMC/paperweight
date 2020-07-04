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

    val status
        get() = this("status", "-z").getText()

    val ref
        get() = this("rev-parse", "HEAD").getText().replace('\n', ' ').replace(Regex("\\s+"), "")

    operator fun invoke(vararg args: String, disableGpg: Boolean = true): Command {
        val cmd = if (disableGpg) {
            arrayOf("git", "-c", "commit.gpgsign=false", *args)
        } else {
            arrayOf("git", *args)
        }
        return try {
            Command(Runtime.getRuntime().exec(cmd, null, repo), cmd.joinToString(separator = " "))
        } catch (e: IOException) {
            throw PaperweightException("Failed to execute command: ${cmd.joinToString(separator = " ")}", e)
        }
    }
}

class Command(internal val process: Process, private val command: String) {

    var outStream: OutputStream? = null

    fun run(): Int = try {
        outStream?.let { out ->
            process.inputStream.copyTo(out)
        }
        process.waitFor()
    } catch (e: Exception) {
        throw PaperweightException("Failed to call git command: $command", e)
    }

    fun runSilently(silenceOut: Boolean = true, silenceErr: Boolean = false): Int {
        silence(silenceOut, silenceErr)
        return run()
    }

    fun runOut(): Int {
        setup(System.out, System.out)
        return run()
    }

    fun execute() {
        val res = run()
        if (res != 0) {
            throw PaperweightException("Command finished with $res exit code: $command")
        }
    }

    fun executeSilently(silenceOut: Boolean = true, silenceErr: Boolean = false) {
        silence(silenceOut, silenceErr)
        execute()
    }

    private fun silence(silenceOut: Boolean, silenceErr: Boolean) {
        val out = if (silenceOut) UselessOutputStream else System.out
        val err = if (silenceErr) UselessOutputStream else System.err
        setup(out, err)
    }

    fun executeOut() {
        setup(System.out, System.err)
        execute()
    }

    fun setup(out: OutputStream? = null, err: OutputStream? = null): Command {
        outStream = out
        if (err != null) {
            redirect(process.errorStream, err)
        }
        return this
    }

    fun getText(): String {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun readText(): String? {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        return if (run() == 0) String(out.toByteArray(), Charsets.UTF_8) else null
    }
}
