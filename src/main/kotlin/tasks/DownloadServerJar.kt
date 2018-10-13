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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.ensureParentExists
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.math.BigInteger
import java.net.URL
import java.security.MessageDigest

open class DownloadServerJar : DefaultTask() {

    @get:Input lateinit var downloadUrl: String
    @get:Input lateinit var hash: String

    @get:OutputFile lateinit var outputJar: Any

    @TaskAction
    fun doStuff() {
        val file = project.file(outputJar)
        ensureParentExists(file)
        ensureDeleted(file)

        file.outputStream().buffered().use { out ->
            URL(downloadUrl).openStream().copyTo(out)
        }

        val digest = MessageDigest.getInstance("MD5")

        val data = file.readBytes()
        val hashResult = digest.digest(data)
        val hashText = String.format("%032x", BigInteger(1, hashResult))

        if (hash != hashText) {
            throw PaperweightException("Checksum failed, expected $hash, actually got $hashText")
        }
    }
}
