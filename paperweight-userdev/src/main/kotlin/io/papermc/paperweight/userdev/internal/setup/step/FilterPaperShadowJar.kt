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

package io.papermc.paperweight.userdev.internal.setup.step

import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.siblingHashesFile
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

class FilterPaperShadowJar(
    @Input private val sourcesJar: Path,
    @Input private val inputJar: Path,
    @Output private val outputJar: Path,
    private val relocations: List<Relocation>,
) : SetupStep {
    override val name: String = "filter mojang mapped paper jar"

    override val hashFile: Path = outputJar.siblingHashesFile()

    override fun run(context: SetupHandler.Context) {
        filterPaperJar(sourcesJar, inputJar, outputJar, relocations)
    }

    override fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {
        builder.include(gson.toJson(relocations))
    }

    private fun filterPaperJar(
        sourcesJar: Path,
        inputJar: Path,
        outputJar: Path,
        relocations: List<Relocation>
    ) {
        val includes = arrayListOf<String>()
        // Include relocated packages
        for (relocation in relocations.map { RelocationWrapper(it) }) {
            includes += '/' + relocation.toSlash + "/**"
            for (exclude in relocation.relocation.excludes) {
                includes += '/' + exclude.replace('.', '/')
            }
        }

        val includedFiles = collectIncludes(sourcesJar, inputJar)
        filterJar(
            inputJar,
            outputJar,
            includes
        ) { path ->
            val str = path.pathString
            if (str.contains('$')) {
                includedFiles.contains(str.split("$")[0] + ".class")
            } else {
                includedFiles.contains(str)
            }
        }
    }

    private fun collectIncludes(
        sourcesJar: Path,
        inputJar: Path
    ): Set<String> {
        val extraIncludes = hashSetOf<String>()

        // Include all files we have sources for
        iterateJar(sourcesJar) { entry ->
            if (entry.isRegularFile()) {
                val string = entry.pathString
                extraIncludes += if (string.endsWith(".java")) {
                    string.substringBeforeLast(".java") + ".class"
                } else {
                    string
                }
            }
        }

        // Include non-class resource files from server jar
        iterateJar(inputJar) { entry ->
            if (entry.isRegularFile() && !entry.name.endsWith(".class")) {
                extraIncludes += entry.pathString
            }
        }

        return extraIncludes
    }

    private fun iterateJar(
        jar: Path,
        visitor: (Path) -> Unit
    ) = jar.openZip().use { fs ->
        fs.walk().use { stream ->
            stream.forEach { path ->
                visitor(path)
            }
        }
    }
}
