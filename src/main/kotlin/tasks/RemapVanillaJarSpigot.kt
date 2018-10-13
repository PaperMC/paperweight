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

    @get:InputFile lateinit var inputJar: Any

    @get:InputFile lateinit var classMappings: Any
    @get:InputFile lateinit var memberMappings: Any
    @get:InputFile lateinit var packageMappings: Any
    @get:InputFile lateinit var accessTransformers: Any
    @get:InputFile lateinit var specialSourceJar: Any
    @get:InputFile lateinit var specialSource2Jar: Any

    @get:OutputFile lateinit var outputJar: Any

    @TaskAction
    fun doStuff() {
        val inputJarPath = project.file(inputJar).canonicalPath

        val outputJarFile = project.file(outputJar)
        val outputJarPath  = outputJarFile.canonicalPath

        val classJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".classes")
        val membersJarFile = outputJarFile.resolveSibling(outputJarFile.name + ".members")
        val classJarPath = classJarFile.canonicalPath
        val membersJarPath = membersJarFile.canonicalPath

        val classMappingPath = project.file(classMappings).canonicalPath
        val memberMappingsPath = project.file(memberMappings).canonicalPath
        val packageMappingsPath = project.file(packageMappings).canonicalPath
        val accessTransformersPath = project.file(accessTransformers).canonicalPath

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
