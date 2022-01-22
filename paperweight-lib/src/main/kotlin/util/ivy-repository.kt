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

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.stream.XMLOutputFactory
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
    dependencies: List<String>,
    binaryJar: Path,
    sourcesJar: Path?,
): Boolean {
    val (module, versionDir) = parseModuleLocation(artifactCoordinates, repo)
    val (_, name, version) = module

    versionDir.createDirectories()

    val sourcesDestination = versionDir.resolve("$name-$version-sources.jar")
    val jarDestination = versionDir.resolve("$name-$version.jar")

    val ivy = versionDir.resolve("ivy-$version.xml")
    val xml = writeIvyModule(module, dependencies.map { parseModule(it) })

    val upToDate = upToDate(sourcesDestination, jarDestination, ivy, sourcesJar, binaryJar, xml)
    if (upToDate) {
        return false
    }

    if (sourcesJar == null) {
        sourcesDestination.deleteIfExists()
    } else {
        sourcesJar.copyTo(sourcesDestination, overwrite = true)
    }
    binaryJar.copyTo(jarDestination, overwrite = true)
    ivy.writeText(xml, Charsets.UTF_8)

    return true
}

private fun upToDate(
    sourcesDest: Path,
    binDest: Path,
    ivyXml: Path,
    sourcesIn: Path?,
    binaryIn: Path,
    xmlIn: String
): Boolean {
    val bin = binDest.isRegularFile() && binDest.sha256asHex() == binaryIn.sha256asHex()
    val xml = ivyXml.isRegularFile() && ivyXml.readText(Charsets.UTF_8) == xmlIn
    val sources = if (sourcesIn == null) {
        sourcesDest.notExists()
    } else {
        sourcesDest.isRegularFile() && sourcesDest.sha256asHex() == sourcesIn.sha256asHex()
    }
    return bin && xml && sources
}

private val OUTPUT_FACTORY = XMLOutputFactory.newInstance()
private const val XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI
private const val IVY = "http://ant.apache.org/ivy/schemas/ivy.xsd"

private fun writeIvyModule(
    module: Module,
    dependencies: List<Module>
): String = ByteArrayOutputStream().use { outputStream ->
    val writer = OUTPUT_FACTORY.createXMLStreamWriter(outputStream, Charsets.UTF_8.name())

    writer.writeStartDocument("UTF-8", "1.0")
    writer.writeStartElement("ivy-module")
    writer.writeNamespace("xsi", XSI)
    writer.writeAttribute(XSI, "noNamespaceSchemaLocation", IVY)
    writer.writeAttribute("version", "2.0")

    writer.writeEmptyElement("info")
    writer.writeAttribute("organisation", module.group)
    writer.writeAttribute("module", module.name)
    writer.writeAttribute("revision", module.version)
    writer.writeAttribute("status", "release")

    writer.writeStartElement("dependencies")
    for (dep in dependencies) {
        writer.writeEmptyElement("dependency")
        writer.writeAttribute("org", dep.group)
        writer.writeAttribute("name", dep.name)
        writer.writeAttribute("rev", dep.version)
    }
    writer.writeEndElement()

    writer.writeEndElement()
    writer.writeEndDocument()

    String(outputStream.toByteArray(), Charsets.UTF_8)
}

private fun parseModule(coordinatesString: String): Module {
    val parts = coordinatesString.split(":")
    val group = parts[0]
    val name = parts[1]
    val version = parts[2]
    return Module(group, name, version)
}

private fun parseModuleLocation(coordinatesString: String, root: Path): ModuleLocation {
    val (group, name, version) = parseModule(coordinatesString)
    val versionDir = root / group.replace(".", "/") / name / version
    return ModuleLocation(Module(group, name, version), versionDir)
}

private data class Module(
    val group: String,
    val name: String,
    val version: String,
)

private data class ModuleLocation(
    val module: Module,
    val versionDir: Path,
)
