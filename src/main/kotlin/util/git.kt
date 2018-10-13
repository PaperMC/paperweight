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

import io.papermc.paperweight.PaperweightException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class Git(internal var repo: File) {

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

internal class Command(internal val process: Process, private val command: String) {

    var outStream: OutputStream? = null

    fun run(): Int = try {
        outStream?.let { out ->
            process.inputStream.copyTo(out)
        }
        process.waitFor()
    } catch (e: Exception) {
        throw PaperweightException("Failed to call git command: $command", e)
    }

    fun runSilently(): Int {
        setup(UselessOutputStream, System.err)
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

    fun executeSilently() {
        setup(UselessOutputStream, System.err)
        execute()
    }

    fun executeOut() {
        setup(System.out, System.out)
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
