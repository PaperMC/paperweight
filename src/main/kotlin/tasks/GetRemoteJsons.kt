/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.Constants.paperTaskOutput
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.ext
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.getWithEtag
import io.papermc.paperweight.util.gson
import io.papermc.paperweight.util.toProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import util.MinecraftManifest

open class GetRemoteJsons : DefaultTask() {

    @Input
    val config: Property<String> = project.objects.property()

    @OutputFile
    val artifactOutputFile: RegularFileProperty = defaultOutput("mc-libraries", "txt")

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val cache = project.cache
        val extension = project.ext

        val mcManifestJson = cache.resolve(Constants.MC_MANIFEST)
        val mcVersionJson = cache.resolve(Constants.VERSION_JSON)
        ensureParentExists(mcManifestJson, mcVersionJson)

        // McManifest.json
        val mcManifestEtag = cache.resolve("${Constants.MC_MANIFEST}.etag")
        getWithEtag(Constants.MC_MANIFEST_URL, mcManifestJson, mcManifestEtag)
        val mcManifestText = gson.fromJson<MinecraftManifest>(mcManifestJson.readText())

        val mcVersionEtag = mcVersionJson.resolveSibling("${mcVersionJson.name}.etag")
        getWithEtag(mcManifestText.versions.first { it.id == extension.minecraftVersion.get() }.url, mcVersionJson, mcVersionEtag)

        val jsonObject = mcVersionJson.bufferedReader().use { reader ->
            gson.fromJson<JsonObject>(reader)
        }

        val conf = config.get()
        artifactOutputFile.file.delete()
        artifactOutputFile.file.bufferedWriter().use { writer ->
            for (library in jsonObject["libraries"].array) {
                val artifact = library["name"].string
                project.dependencies.add(conf, artifact)
                writer.appendln(artifact)
            }
        }
    }
}
