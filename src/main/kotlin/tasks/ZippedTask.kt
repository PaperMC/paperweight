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
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.unzip
import io.papermc.paperweight.util.zip
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ThreadLocalRandom

// ZippedTask attempts to encapsulate all of the major inputs and outputs of a particular task into single zip files
abstract class ZippedTask : DefaultTask() {

    @get:InputFile @get:Optional var inputZip: Any? = null

    @get:OutputFile val outputZip by Constants.taskOutput()

    @get:OutputFile @get:Optional var customOutputZip: Any? = null

    abstract fun action(rootDir: File)

    @TaskAction
    fun doStuff() {
        val inputZipFile = inputZip?.let { project.file(it) }
        val outputZipFile  = customOutputZip?.let { customOutputZip ->
            project.file(customOutputZip)
        } ?:  project.file(outputZip)

        val dir = outputZipFile.resolveSibling("${outputZipFile.name}-" + ThreadLocalRandom.current().nextInt())
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
