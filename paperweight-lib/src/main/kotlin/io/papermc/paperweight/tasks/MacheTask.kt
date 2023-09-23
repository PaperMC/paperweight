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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.mache.*
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction

abstract class MacheTask : DefaultTask() {

    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val meta = getMacheMeta()
        println("loaded meta: $meta")
    }

    private fun getMacheMeta(): MacheMeta {
        val metaJson = mutableListOf("")
        mache.singleFile.toPath().openZip().use { zip ->
            metaJson.addAll(zip.getPath("/mache.json").readLines())
        }

        return gson.fromJson<MacheMeta>(metaJson.joinToString("\n"))
    }

    private fun extractVanillaJar() {
        // val jar = downloadedJar.convertToPath()
        // val out = serverJar.convertToPath().ensureClean()
//
        // jar.useZip { root ->
        //    val metaInf = root.resolve("META-INF")
        //    val versionsList = metaInf.resolve("versions.list")
        //    if (versionsList.notExists()) {
        //        throw Exception("Could not find versions.list")
        //    }
//
        //    val lines = versionsList.readLines()
        //    if (lines.size != 1) {
        //        throw Exception("versions.list is invalid")
        //    }
//
        //    val line = lines.first()
        //    val parts = line.split(whitespace)
        //    if (parts.size != 3) {
        //        throw Exception("versions.list line is invalid")
        //    }
//
        //    val serverJarInJar = metaInf.resolve("versions").resolve(parts[2])
        //    if (serverJarInJar.notExists()) {
        //        throw Exception("Could not find version jar")
        //    }
//
        //    serverJarInJar.copyTo(out)
        // }
    }
}
