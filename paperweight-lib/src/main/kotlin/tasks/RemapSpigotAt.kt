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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.Constants
import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.openZip
import io.papermc.paperweight.util.path
import kotlin.io.path.*
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class RemapSpigotAt : BaseTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val spigotAt: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        outputFile.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        val outputAt = AccessTransformSet.create()

        spigotAt.path.useLines { lines ->
            inputJar.path.openZip().use { jarFile ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith('#')) {
                        continue
                    }

                    val (access, desc) = line.split(' ')

                    if (desc.contains('(')) {
                        // method
                        val index = desc.indexOf('(')
                        val methodDesc = desc.substring(index)
                        val classAndMethodName = desc.substring(0, index)
                        val slashIndex = classAndMethodName.lastIndexOf('/')
                        val className = classAndMethodName.substring(0, slashIndex)
                        val methodName = classAndMethodName.substring(slashIndex + 1)

                        outputAt.getOrCreateClass(className).replaceMethod(
                            MethodSignature(methodName, MethodDescriptor.of(methodDesc)),
                            parseAccess(access)
                        )
                    } else {
                        // either field or class
                        if (jarFile.getPath("$desc.class").notExists()) {
                            // field
                            val index = desc.lastIndexOf('/')
                            val className = desc.substring(0, index)
                            val fieldName = desc.substring(index + 1)
                            outputAt.getOrCreateClass(className).replaceField(fieldName, parseAccess(access))
                        } else {
                            // class
                            outputAt.getOrCreateClass(desc).replace(parseAccess(access))
                        }
                    }
                }
            }
        }

        val mappings = MappingFormats.TINY.read(mapping.path, Constants.SPIGOT_NAMESPACE, Constants.DEOBF_NAMESPACE)
        val remappedAt = outputAt.remap(mappings)

        AccessTransformFormats.FML.write(outputFile.path, remappedAt)
    }

    private fun parseAccess(text: String): AccessTransform {
        val index = text.indexOfAny(charArrayOf('+', '-'))
        return if (index == -1) {
            // only access
            AccessTransform.of(parseAccessChange(text))
        } else {
            val accessChange = parseAccessChange(text.substring(0, index))
            val modifierChange = parseModifierChange(text[index])
            AccessTransform.of(accessChange, modifierChange)
        }
    }

    private fun parseAccessChange(text: String): AccessChange {
        return when (text) {
            "public" -> AccessChange.PUBLIC
            "private" -> AccessChange.PRIVATE
            "protected" -> AccessChange.PROTECTED
            "default" -> AccessChange.PACKAGE_PRIVATE
            else -> AccessChange.NONE
        }
    }

    private fun parseModifierChange(c: Char): ModifierChange {
        return when (c) {
            '+' -> ModifierChange.ADD
            '-' -> ModifierChange.REMOVE
            else -> ModifierChange.NONE
        }
    }
}
