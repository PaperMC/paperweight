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
import io.papermc.paperweight.util.runJar
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.concurrent.ThreadLocalRandom

open class DecompileVanillaJar : DefaultTask() {

    @get:InputFile lateinit var inputJar: Any
    @get:InputFile lateinit var fernFlowerJar: Any

    @get:OutputFile lateinit var outputJar: Any

    @TaskAction
    fun doStuff() {
        val inputJarFile = project.file(inputJar)
        val inputJarPath = inputJarFile.canonicalPath

        val outputJarFile = project.file(outputJar)
        val decomp = outputJarFile.parentFile.resolve("decomp" + ThreadLocalRandom.current().nextInt())

        try {
            if (!decomp.exists() && !decomp.mkdirs()) {
                throw PaperweightException("Failed to create output directory: $decomp")
            }

            runJar(fernFlowerJar, "-dgs=1", "-hdc=0", "-asc=1", "-udv=0", inputJarPath, decomp.canonicalPath)

            ensureDeleted(outputJarFile)
            decomp.resolve(inputJarFile.name).copyTo(outputJarFile, overwrite = true)
        } finally {
            decomp.deleteRecursively()
        }
    }
}
