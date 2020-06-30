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
import org.gradle.kotlin.dsl.property
import java.math.BigInteger
import java.net.URL
import java.security.MessageDigest

open class DownloadServerJar : DefaultTask() {

    @Input
    val downloadUrl = project.objects.property<String>()
    @Input
    val hash = project.objects.property<String>()

    @OutputFile
    val outputJar = project.objects.fileProperty()

    @TaskAction
    fun doStuff() {
        val file = outputJar.asFile.get()
        ensureParentExists(file)
        ensureDeleted(file)

        file.outputStream().buffered().use { out ->
            URL(downloadUrl.get()).openStream().copyTo(out)
        }

        val digest = MessageDigest.getInstance("MD5")

        val data = file.readBytes()
        val hashResult = digest.digest(data)
        val hashText = String.format("%032x", BigInteger(1, hashResult))

        if (hash.get() != hashText) {
            throw PaperweightException("Checksum failed, expected ${hash.get()}, actually got $hashText")
        }
    }
}
