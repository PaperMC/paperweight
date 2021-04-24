/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.MappingFormats
import io.papermc.paperweight.util.defaultOutput
import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.file
import io.papermc.paperweight.util.path
import javax.inject.Inject
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.cadixdev.lorenz.asm.LorenzRemapper
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.commons.Remapper

abstract class RemapJarAtlas : BaseTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    abstract val mappingsFile: RegularFileProperty

    @get:Input
    abstract val packageVersion: Property<String>

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    override fun init() {
        outputJar.convention(defaultOutput())
    }

    @TaskAction
    fun run() {
        ensureParentExists(outputJar)
        ensureDeleted(outputJar)

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx1G")
        }

        queue.submit(AtlasAction::class) {
            inputJar.set(this@RemapJarAtlas.inputJar.file)
            outputJar.set(this@RemapJarAtlas.outputJar.file)
            mappingsFile.set(this@RemapJarAtlas.mappingsFile.file)
            packageVersion.set(this@RemapJarAtlas.packageVersion.get())
            toNamespace.set(this@RemapJarAtlas.toNamespace.get())
            fromNamespace.set(this@RemapJarAtlas.fromNamespace.get())
        }
    }

    abstract class AtlasAction : WorkAction<AtlasParameters> {
        override fun execute() {
            val mappings = MappingFormats.TINY.read(parameters.mappingsFile.path, parameters.fromNamespace.get(), parameters.toNamespace.get())

            val oldPack = "net/minecraft"
            val newPack = "$oldPack/server/v${parameters.packageVersion.get()}"
            Atlas().let { atlas ->
                atlas.install { ctx -> JarEntryRemappingTransformer(LorenzRemapper(mappings, ctx.inheritanceProvider())) }
                atlas.install { JarEntryRemappingTransformer(PackageRemapper(oldPack, newPack)) }
                atlas.run(parameters.inputJar.path, parameters.outputJar.path)
            }
        }
    }

    interface AtlasParameters : WorkParameters {
        val inputJar: RegularFileProperty
        val outputJar: RegularFileProperty
        val mappingsFile: RegularFileProperty
        val fromNamespace: Property<String>
        val toNamespace: Property<String>
        val packageVersion: Property<String>
    }
}

class PackageRemapper(private val oldPackage: String, private val newPackage: String) : Remapper() {

    override fun map(internalName: String): String {
        return if (internalName.startsWith(oldPackage)) {
            internalName.replaceFirst(oldPackage, newPackage)
        } else {
            internalName
        }
    }
}
