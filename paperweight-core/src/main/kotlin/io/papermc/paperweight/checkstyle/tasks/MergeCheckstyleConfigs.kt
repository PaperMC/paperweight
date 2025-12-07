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

package io.papermc.paperweight.checkstyle.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.deleteForcefully
import io.papermc.paperweight.util.path
import java.nio.file.Path
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import org.gradle.api.file.BuildLayout
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

abstract class MergeCheckstyleConfigs : BaseTask() {

    @get:InputFile
    abstract val baseConfigFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val overrideConfigFile: RegularFileProperty

    @get:OutputFile
    abstract val mergedConfigFile: RegularFileProperty

    @get:Inject
    abstract val buildLayout: BuildLayout

    @get:Inject
    abstract val projectLayout: ProjectLayout

    override fun init() {
        baseConfigFile.convention(buildLayout.rootDirectory.file(".checkstyle/checkstyle_base.xml"))
        overrideConfigFile.convention(projectLayout.projectDirectory.file(".checkstyle/checkstyle.xml"))
        mergedConfigFile.convention(projectLayout.buildDirectory.file("$name/merged_config.xml"))
    }

    @TaskAction
    fun run() {
        mergedConfigFile.path.deleteForcefully()
        if (overrideConfigFile.isPresent && overrideConfigFile.path.exists()) {
            mergeCheckstyleConfigs(baseConfigFile.path, overrideConfigFile.path, mergedConfigFile.path)
        } else {
            mergedConfigFile.path.parent.createDirectories()
            baseConfigFile.path.copyTo(mergedConfigFile.path)
        }
    }

    private fun mergeCheckstyleConfigs(baseFile: Path, additionalFile: Path, outputFile: Path) {
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

    private fun mergeModules(baseDoc: Document, baseParent: Element, additionalParent: Element) {
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

    private fun findDirectChildModule(parent: Element, name: String): Element? {
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
