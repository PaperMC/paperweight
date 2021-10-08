/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.w3c.dom.Element

@CacheableTask
abstract class DetermineSpigotDependencies : BaseTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverPom: RegularFileProperty

    @get:OutputFile
    abstract val dependencies: RegularFileProperty

    @get:OutputFile
    abstract val repositories: RegularFileProperty

    override fun init() {
        dependencies.convention(defaultOutput("$name-dependencies", "txt"))
        repositories.convention(defaultOutput("$name-repositories", "txt"))
    }

    @TaskAction
    fun run() {
        val apiSetup = parsePom(apiPom.path)
        val serverSetup = parsePom(serverPom.path)

        val spigotRepos = mutableSetOf<String>()
        spigotRepos += apiSetup.repos
        spigotRepos += serverSetup.repos

        val artifacts = mutableSetOf<MavenArtifact>()
        artifacts += apiSetup.artifacts
        artifacts += serverSetup.artifacts

        dependencies.path.parent.createDirectories()
        dependencies.path.writeLines(artifacts.map { it.toString() })
        repositories.path.writeLines(spigotRepos)
    }

    private fun parsePom(pomFile: Path): MavenSetup {
        val depList = arrayListOf<MavenArtifact>()
        val repoList = arrayListOf<String>()
        // Maven Central is implicit
        repoList += "https://repo.maven.apache.org/maven2/"

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
                val artifact = getDependency(dependency) ?: continue
                depList += artifact
            }
        }

        val repos = doc.getElementsByTagName("repositories")
        for (i in 0 until repos.length) {
            val node = repos.item(i) as? Element ?: continue
            val depNode = node.getElementsByTagName("repository")
            for (j in 0 until depNode.length) {
                val repo = depNode.item(j) as? Element ?: continue
                val repoUrl = repo.getElementsByTagName("url").item(0).textContent
                repoList += repoUrl
            }
        }

        return MavenSetup(repos = repoList, artifacts = depList)
    }

    private fun getDependency(node: Element): MavenArtifact? {
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

        return MavenArtifact(
            group = group,
            artifact = artifact,
            version = version
        )
    }

    data class MavenSetup(
        val repos: List<String>,
        val artifacts: List<MavenArtifact>
    )
}
