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
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.ensureParentExists
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Date

open class ApplyDiffPatches : DefaultTask() {

    @InputFile
    val sourceJar = project.objects.fileProperty()
    @Input
    val sourceBasePath = project.objects.property<String>()
    @InputDirectory
    val patchDir = project.objects.directoryProperty()
    @InputDirectory
    val basePatchDir = project.objects.directoryProperty()
    @Input
    val branch = project.objects.property<String>()

    @OutputDirectory
    val baseDir = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        val baseDirFile = project.file(baseDir)
        val git = Git(baseDirFile)
        git("checkout", "-B", branch.get(), "HEAD").executeSilently()

        val sourceJarFile = project.file(sourceJar)
        val uri = URI.create("jar:${sourceJarFile.toURI()}")

        val patchDirFile = patchDir.asFile.get()
        val basePatchDirFile = basePatchDir.asFile.get()
        val outputDirFile = basePatchDirFile.resolve(sourceBasePath.get())

        val patchList = patchDirFile.listFiles() ?: throw PaperweightException("Patch directory does not exist $patchDirFile")
        if (patchList.isEmpty()) {
            throw PaperweightException("No patch files found in $patchDirFile")
        }

        // Copy in patch targets
        FileSystems.newFileSystem(uri, mapOf<String, Any>()).use { fs ->
            for (file in patchList) {
                val javaName = file.name.replaceAfterLast('.', "java")
                val out = outputDirFile.resolve(javaName)
                val sourcePath = fs.getPath(sourceBasePath.get(), javaName)

                Files.newInputStream(sourcePath).use { input ->
                    ensureParentExists(out)
                    out.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        git("add", "src").executeOut()
        git("commit", "-m", "Minecraft $ ${Date()}", "--author=Auto <auto@mated.null>").executeOut()

        // Apply patches
        for (file in patchList) {
            val javaName = file.name.replaceAfterLast('.', "java")
            println("Patching $javaName < ${file.name}")
            git("apply", "--directory=${basePatchDirFile.relativeTo(baseDirFile).path}", file.absolutePath).executeOut()
        }

        git("add", "src").executeOut()
        git("commit", "-m", "Patched $ ${Date()}", "--author=Auto <auto@mated.null>").executeOut()
        git("checkout", "-f", "HEAD~2").executeSilently()
    }
}
