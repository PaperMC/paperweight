/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 * Copyright (C) 2018 Forge Development LLC
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

import io.papermc.paperweight.util.file
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

open class SetupSpigotDependencies : DefaultTask() {

    @InputDirectory
    val spigotApi: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val spigotServer: DirectoryProperty = project.objects.directoryProperty()

    @Input
    val configurationName: Property<String> = project.objects.property()

    init {
        // we set dependencies here, can't cache this
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val apiDeps = parsePom(spigotApi.file.resolve("pom.xml"))
        val serverDeps = parsePom(spigotServer.file.resolve("pom.xml"))

        for (dep in apiDeps) {
            project.dependencies.add(configurationName.get(), dep)
        }
        for (dep in serverDeps) {
            project.dependencies.add(configurationName.get(), dep)
        }
    }

    private fun parsePom(pomFile: File): List<String> {
        val depList = arrayListOf<String>()

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = pomFile.inputStream().buffered().use { stream ->
            stream.buffered().use { buffered ->
                builder.parse(buffered)
            }
        }

        doc.documentElement.normalize()

        val list = doc.getElementsByTagName("dependencies")
        for (i in 0 until list.length) {
            val node = list.item(i) as? Element ?: continue

            val depNode = node.getElementsByTagName("dependency")
            for (j in 0 until depNode.length) {
                val dependency = depNode.item(j) as? Element ?: continue
                val text = getDependency(dependency) ?: continue
                depList += text
            }
        }

        return depList
    }

    private fun getDependency(node: Element): String? {
        val scopeNode = node.getElementsByTagName("scope")
        val scope = if (scopeNode.length == 0) {
            "compile"
        } else {
            scopeNode.item(0).textContent
        }

        if (scope != "compile") {
            return null
        }

        val group = node.getElementsByTagName("groupId").item(0).textContent
        val artifact = node.getElementsByTagName("artifactId").item(0).textContent
        val version = node.getElementsByTagName("version").item(0).textContent

        if (version.contains("\${")) {
            // Don't handle complicated things
            // We don't need to (for now anyways)
            return null
        }

        return "$group:$artifact:$version"
    }
}
