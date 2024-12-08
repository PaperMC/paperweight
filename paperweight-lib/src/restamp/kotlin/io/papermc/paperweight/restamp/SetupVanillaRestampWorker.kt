/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.restamp

import io.papermc.paperweight.util.path
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Path
import kotlin.io.path.writeText
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.openrewrite.InMemoryExecutionContext

abstract class SetupVanillaRestampWorker : WorkAction<SetupVanillaRestampWorker.Params> {
    interface Params : WorkParameters {
        val ats: RegularFileProperty
        val outputPath: RegularFileProperty
        val classpath: ConfigurableFileCollection
    }

    override fun execute() {
        setupVanillaRestamp(
            parameters.ats,
            parameters.outputPath.path,
            parameters.classpath.files.map { it.toPath() }
        )
    }

    private fun setupVanillaRestamp(ats: RegularFileProperty, outputPath: Path, classPath: List<Path>) {
        val configuration = RestampContextConfiguration.builder()
            .accessTransformers(ats.path, AccessTransformFormats.FML)
            .sourceRoot(outputPath)
            .sourceFilesFromAccessTransformers(false)
            .classpath(classPath)
            .executionContext(InMemoryExecutionContext { it.printStackTrace() })
            .failWithNotApplicableAccessTransformers()
            .build()

        val parsedInput = RestampInput.parseFrom(configuration)
        val results = Restamp.run(parsedInput).allResults

        results.forEach { result ->
            if (result.after != null) {
                outputPath.resolve(result.after!!.sourcePath).writeText(result.after!!.printAll())
            }
        }
    }
}
