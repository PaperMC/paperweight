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

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.w3c.dom.Element
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

open class SetupSpigotDependencyConfig : DefaultTask() {

    @InputFile
    val spigotApiZip = project.objects.fileProperty()
    @InputFile
    val spigotServerZip = project.objects.fileProperty()

    @Input
    val configurationName = project.objects.property<String>()

    init {
        // we set dependencies here, can't cache this
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val spigotApiFile = project.file(spigotApiZip)
        val spigotServerFile = project.file(spigotServerZip)

        val apiDeps = FileSystems.newFileSystem(URI.create("jar:${spigotApiFile.toURI()}"), mapOf<String, Any>()).use { fs ->
            parsePom(fs.getPath("pom.xml"))
        }

        val serverDeps = FileSystems.newFileSystem(URI.create("jar:${spigotServerFile.toURI()}"), mapOf<String, Any>()).use { fs ->
            parsePom(fs.getPath("pom.xml"))
        }

        for (dep in apiDeps) {
            project.dependencies.add(configurationName.get(), dep)
        }
        for (dep in serverDeps) {
            project.dependencies.add(configurationName.get(), dep)
        }
    }

    private fun parsePom(pomPath: Path): List<String> {
        val depList = arrayListOf<String>()

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = Files.newInputStream(pomPath).use { stream ->
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
