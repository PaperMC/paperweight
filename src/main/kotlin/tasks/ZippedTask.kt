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

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.unzip
import io.papermc.paperweight.util.zip
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import java.io.File
import java.util.concurrent.ThreadLocalRandom

// ZippedTask attempts to encapsulate all of the major inputs and outputs of a particular task into single zip files
abstract class ZippedTask : DefaultTask() {

    @InputFile
    @Optional
    val inputZip = project.objects.fileProperty()

    @OutputFile
    val outputZip = project.objects.fileProperty()

    init {
        outputZip.convention {
            project.file(Constants.taskOutput(project, "$name.zip"))
        }
    }

    abstract fun action(rootDir: File)

    @TaskAction
    fun run() {
        val inputZipFile = inputZip.orNull
        val outputZipFile = outputZip.asFile.get()

        var dir: File
        do {
            dir = outputZipFile.resolveSibling("${outputZipFile.name}-" + ThreadLocalRandom.current().nextInt())
        } while (dir.exists())

        try {
            if (inputZipFile != null) {
                unzip(inputZipFile, dir)
            }

            action(dir)

            ensureDeleted(outputZipFile)
            zip(dir, outputZipFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
