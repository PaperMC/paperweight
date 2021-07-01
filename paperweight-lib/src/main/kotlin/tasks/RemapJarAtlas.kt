/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.ensureDeleted
import io.papermc.paperweight.util.ensureParentExists
import io.papermc.paperweight.util.path
import javax.inject.Inject
import org.cadixdev.atlas.Atlas
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.objectweb.asm.commons.Remapper

@CacheableTask
abstract class RemapJarAtlas : BaseTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:Input
    abstract val packageVersion: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun run() {
        ensureParentExists(outputJar)
        ensureDeleted(outputJar)

        val queue = workerExecutor.processIsolation {
            forkOptions.jvmArgs("-Xmx1G")
        }

        queue.submit(AtlasAction::class) {
            inputJar.set(this@RemapJarAtlas.inputJar.get())
            outputJar.set(this@RemapJarAtlas.outputJar.get())
            packageVersion.set(this@RemapJarAtlas.packageVersion.get())
        }
    }

    abstract class AtlasAction : WorkAction<AtlasParameters> {
        override fun execute() {
            val oldPack = "net/minecraft"
            val newPack = "$oldPack/server/v${parameters.packageVersion.get()}"
            Atlas().let { atlas ->
                atlas.install { JarEntryRemappingTransformer(PackageRemapper(oldPack, newPack)) }
                atlas.run(parameters.inputJar.path, parameters.outputJar.path)
            }
        }
    }

    interface AtlasParameters : WorkParameters {
        val inputJar: RegularFileProperty
        val outputJar: RegularFileProperty
        val packageVersion: Property<String>
    }
}

class PackageRemapper(private val oldPackage: String, private val newPackage: String) : Remapper() {

    override fun map(internalName: String): String {
        return if (internalName.startsWith(oldPackage)) {
            internalName.replaceBeforeLast('/', newPackage)
        } else {
            internalName
        }
    }
}
