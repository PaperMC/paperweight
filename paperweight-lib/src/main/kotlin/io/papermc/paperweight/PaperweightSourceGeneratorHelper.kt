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

package io.papermc.paperweight

import io.papermc.paperweight.extension.PaperweightSourceGeneratorExt
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.*

abstract class PaperweightSourceGeneratorHelper : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val ext = extensions.create("paperweight", PaperweightSourceGeneratorExt::class)

        val minecraftJar by configurations.registering

        val applyAts by tasks.registering<ApplyAccessTransform> {
            inputJar.set(layout.file(minecraftJar.flatMap { it.elements }.map { it.single().asFile }))
            atFile.set(ext.atFile)
        }

        val vanilla = configurations.register("vanillaServer")

        dependencies {
            vanilla.name(files(applyAts.flatMap { it.outputJar }))
        }

        afterEvaluate {
            if (ext.addVanillaServerToImplementation.get()) {
                configurations.named("implementation") {
                    extendsFrom(vanilla.get())
                }
            }
        }
    }
}
