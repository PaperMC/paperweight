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

package io.papermc.paperweight.checkstyle

import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.resources.TextResourceFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

abstract class PaperCheckstyleTask : Checkstyle() {

    @get:Input
    abstract val rootPath: Property<String>

    @get:InputFile
    @get:Optional
    abstract val directoriesToSkipFile: RegularFileProperty

    @get:InputFile
    abstract val typeUseAnnotationsFile: RegularFileProperty

    @get:Nested
    @get:Optional
    abstract val customJavadocTags: SetProperty<JavadocTag>

    @get:InputFile
    abstract val baseConfigFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val overrideConfigFile: RegularFileProperty

    @get:OutputFile
    abstract val mergedConfigFile: RegularFileProperty

    @get:Internal
    val textResourceFactory: TextResourceFactory = project.resources.text

    init {
        reports.xml.required.set(true)
        reports.html.required.set(true)
        maxHeapSize.set("2g")
        configDirectory.set(project.rootProject.layout.projectDirectory.dir(".checkstyle"))
        baseConfigFile.convention(project.rootProject.layout.projectDirectory.file(".checkstyle/checkstyle_base.xml"))
        overrideConfigFile.convention(project.layout.projectDirectory.file(".checkstyle/checkstyle.xml"))

        mergedConfigFile.convention(project.layout.buildDirectory.file("checkstyle/merged_config.xml"))
    }

    @TaskAction
    override fun run() {
        mergedConfigFile.path.deleteForcefully()
        if (overrideConfigFile.isPresent && overrideConfigFile.path.exists()) {
            mergeCheckstyleConfigs(baseConfigFile.path, overrideConfigFile.path, mergedConfigFile.path)
            config = textResourceFactory.fromFile(mergedConfigFile.path.toFile())
        } else {
            config = textResourceFactory.fromFile(baseConfigFile.path.toFile())
        }
        val existingProperties = configProperties?.toMutableMap() ?: mutableMapOf()
        existingProperties["type_use_annotations"] = typeUseAnnotationsFile.path.readText().trim().split("\n").joinToString("|")
        existingProperties["custom_javadoc_tags"] = customJavadocTags.getOrElse(emptySet()).joinToString("|") { it.toOptionString() }
        configProperties = existingProperties
        exclude {
            if (it.isDirectory) return@exclude false
            val absPath = it.file.toPath().toAbsolutePath().relativeTo(Paths.get(rootPath.get()))
            val parentPath = (absPath.parent?.invariantSeparatorsPathString + "/")
            if (directoriesToSkipFile.isPresent) {
                return@exclude directoriesToSkipFile.path.readText().trim().split("\n").any { pkg -> parentPath == pkg }
            }
            return@exclude false
        }
        if (!source.isEmpty) {
            super.run()
        }
    }

    fun mergeCheckstyleConfigs(baseFile: Path, additionalFile: Path, outputFile: Path) {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        val baseDoc = builder.parse(InputSource(baseFile.bufferedReader()))
        val additionalDoc = builder.parse(InputSource(additionalFile.bufferedReader()))

        baseDoc.documentElement.normalize()
        additionalDoc.documentElement.normalize()

        mergeModules(baseDoc, baseDoc.documentElement, additionalDoc.documentElement)

        outputFile.createParentDirectories()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.setOutputProperty(
            javax.xml.transform.OutputKeys.DOCTYPE_PUBLIC,
            "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        )
        transformer.setOutputProperty(
            javax.xml.transform.OutputKeys.DOCTYPE_SYSTEM,
            "https://checkstyle.org/dtds/configuration_1_3.dtd"
        )
        transformer.transform(DOMSource(baseDoc), StreamResult(outputFile.bufferedWriter()))
    }

    fun mergeModules(baseDoc: Document, baseParent: Element, additionalParent: Element) {
        val additionalChildren = additionalParent.childNodes

        for (i in 0 until additionalChildren.length) {
            val child = additionalChildren.item(i)

            if (child is Element && child.nodeName == "module") {
                val moduleName = child.getAttribute("name")
                val shouldMerge = moduleName in setOf("Checker", "TreeWalker")

                if (shouldMerge) {
                    val existingModule = findDirectChildModule(baseParent, moduleName)

                    if (existingModule != null) {
                        mergeModules(baseDoc, existingModule, child)
                    } else {
                        val importedNode = baseDoc.importNode(child, true)
                        baseParent.appendChild(importedNode)
                    }
                } else {
                    val importedNode = baseDoc.importNode(child, true)
                    baseParent.appendChild(importedNode)
                }
            }
        }
    }

    fun findDirectChildModule(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.nodeName == "module" && child.getAttribute("name") == name) {
                return child
            }
        }
        return null
    }
}
