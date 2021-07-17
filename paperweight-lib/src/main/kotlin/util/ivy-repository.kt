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

package io.papermc.paperweight.util

import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.kotlin.dsl.*

fun RepositoryHandler.setupIvyRepository(
    url: Any,
    configuration: IvyArtifactRepository.() -> Unit
) = ivy(url) {
    patternLayout {
        artifact(IvyArtifactRepository.MAVEN_ARTIFACT_PATTERN)
        ivy(IvyArtifactRepository.MAVEN_IVY_PATTERN)
        setM2compatible(true)
    }
    metadataSources(IvyArtifactRepository.MetadataSources::ivyDescriptor)
    isAllowInsecureProtocol = true
    resolve.isDynamicMode = false
    configuration(this)
}

fun installToIvyRepo(
    repo: Path,
    artifactCoordinates: String,
    sourcesJar: Path,
    binaryJar: Path
): Boolean {
    val (group, name, version, versionDir) = parseCoordinates(artifactCoordinates, repo)

    versionDir.createDirectories()

    val sourcesDestination = versionDir.resolve("$name-$version-sources.jar")
    val jarDestination = versionDir.resolve("$name-$version.jar")

    val upToDate = sourcesDestination.isRegularFile() && jarDestination.isRegularFile() &&
        sourcesDestination.sha256asHex() == sourcesJar.sha256asHex() &&
        jarDestination.sha256asHex() == binaryJar.sha256asHex()
    if (upToDate) {
        return false
    }

    sourcesJar.copyTo(sourcesDestination, overwrite = true)
    binaryJar.copyTo(jarDestination, overwrite = true)

    val ivy = versionDir.resolve("ivy-$version.xml")
    // If at some point we want to specify transitive dependencies though ivy metadata, it might be worth
    // implementing this in a more proper way. For now, this works just fine.
    val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ivy-module xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://ant.apache.org/ivy/schemas/ivy.xsd" version="2.0">
            <info organisation="$group" module="$name" revision="$version" status="release">
            </info>
            <dependencies>
            </dependencies>
        </ivy-module>

    """.trimIndent()
    ivy.writeText(xml, Charsets.UTF_8)
    return true
}

private fun parseCoordinates(coordinatesString: String, root: Path): ArtifactLocation {
    val parts = coordinatesString.split(":")
    val group = parts[0]
    val groupDir = root.resolve(group.replace(".", "/"))
    val name = parts[1]
    val nameDir = groupDir.resolve(name)
    val version = parts[2]
    val versionDir = nameDir.resolve(version)
    return ArtifactLocation(group, name, version, versionDir)
}

private data class ArtifactLocation(
    val group: String,
    val name: String,
    val version: String,
    val versionDir: Path
)
