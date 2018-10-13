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

import de.oceanlabs.mcp.mcinjector.MCInjector
import de.oceanlabs.mcp.mcinjector.lvt.LVTNaming
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.ensureParentExists
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.survey.SurveyMapper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

open class RemapVanillaJarSrg : DefaultTask() {

    @get:InputFile lateinit var inputJar: Any

    @get:InputFile lateinit var mappings: Any
    @get:InputFile lateinit var access: Any
    @get:InputFile lateinit var constructors: Any
    @get:InputFile lateinit var exceptions: Any

    @get:OutputFile lateinit var outputJar: Any

    @TaskAction
    fun doStuff() {
        val inputJarFile = project.file(inputJar)
        val outputJarFile = project.file(outputJar)
        val mappingsFile = project.file(mappings)

        ensureParentExists(inputJarFile, outputJarFile)

        val surveyOut = outputJarFile.resolveSibling("temp_survey.jar")

        try {
            ensureDeleted(surveyOut)
            SurveyMapper().loadMappings(mappingsFile.toPath(), MappingFormats.TSRG)
                .remap(inputJarFile.toPath(), surveyOut.toPath())

            val mcInjectorLog = outputJarFile.resolveSibling("mcinjector.log")
            ensureDeleted(mcInjectorLog, outputJarFile)

            // We have to deal with the fact that gradle runs in daemons...
            for (handler in MCInjector.LOG.handlers) {
                MCInjector.LOG.removeHandler(handler)
                handler.close()
            }

            MCInjector(surveyOut.toPath(), outputJarFile.toPath()).apply {
                access(project.file(access).toPath())
                constructors(project.file(constructors).toPath())
                exceptions(project.file(exceptions).toPath())
                lvt(LVTNaming.LVT)
                log(mcInjectorLog.toPath())
                process()
            }

            ensureDeleted(mcInjectorLog.resolveSibling(mcInjectorLog.name + ".lck"))
        } finally {
            surveyOut.delete()
        }
    }
}
