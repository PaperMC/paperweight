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

import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.unzip
import io.papermc.paperweight.util.zip
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

open class ApplyMcpPatches : DefaultTask() {

    @InputFile
    val inputJar = project.objects.fileProperty()
    @InputDirectory
    val serverPatchDir = project.objects.directoryProperty()

    @OutputFile
    val outputJar = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val dir = unzip(inputJar)
        try {
            val serverPatchDirFile = project.file(serverPatchDir)

            val git = Git(dir)

            serverPatchDirFile.walkBottomUp()
                .filter { it.isFile && it.name.endsWith(".patch") }
                .forEach { patch ->
                    git("apply", "--ignore-whitespace", patch.absolutePath, disableGpg = false).runSilently()
                }

            zip(dir, outputJar)
        } finally {
            dir.deleteRecursively()
        }
    }
}
