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

import io.papermc.paperweight.util.runJar
import io.papermc.paperweight.util.wrapException
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

open class RemapVanillaJarSpigot : DefaultTask() {

    @InputFile
    val inputJar = project.objects.fileProperty()

    @InputFile
    val classMappings = project.objects.fileProperty()
    @InputFile
    val memberMappings = project.objects.fileProperty()
    @InputFile
    val packageMappings = project.objects.fileProperty()
    @InputFile
    val accessTransformers = project.objects.fileProperty()
    @InputFile
    val specialSourceJar = project.objects.fileProperty()
    @InputFile
    val specialSource2Jar = project.objects.fileProperty()

    @OutputFile
    val outputJar = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val inputJarPath = inputJar.asFile.get().canonicalPath

        val outputJarFile = outputJar.asFile.get()
        val outputJarPath  = outputJarFile.canonicalPath

        val classJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".classes")
        val membersJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".members")
        val classJarPath = classJarFile.canonicalPath
        val membersJarPath = membersJarFile.canonicalPath

        val classMappingPath = classMappings.asFile.get().canonicalPath
        val memberMappingsPath = memberMappings.asFile.get().canonicalPath
        val packageMappingsPath = packageMappings.asFile.get().canonicalPath
        val accessTransformersPath = accessTransformers.asFile.get().canonicalPath

        println("Applying class mappings...")
        wrapException("Failed to apply class mappings") {
            runJar(specialSource2Jar, "map", "-i", inputJarPath, "-m", classMappingPath, "-o", classJarPath)
        }
        println("Applying member mappings...")
        wrapException("Failed to apply member mappings") {
            runJar(specialSource2Jar, "map", "-i", classJarPath, "-m", memberMappingsPath, "-o", membersJarPath)
        }
        println("Creating remapped jar...")
        wrapException("Failed to create remapped jar") {
            runJar(specialSourceJar, "--kill-lvt", "-i", membersJarPath, "--access-transformer", accessTransformersPath, "-m", packageMappingsPath, "-o", outputJarPath)
        }

        classJarFile.delete()
        membersJarFile.delete()
    }
}
