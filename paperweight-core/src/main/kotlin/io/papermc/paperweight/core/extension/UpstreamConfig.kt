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

package io.papermc.paperweight.core.extension

import io.papermc.paperweight.util.*
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.kotlin.dsl.*

abstract class UpstreamConfig @Inject constructor(
    private val configName: String,
    private val objects: ObjectFactory,
    // TODO: Support single file patches for -server (i.e. .editorconfig)
    val singleFilePatches: Boolean,
) : Named {
    val paper: Property<Boolean> = objects.property<Boolean>().convention(false)
    abstract val repo: Property<String>
    abstract val ref: Property<String>
    abstract val patchSets: ListProperty<PatchSet>

    override fun getName(): String {
        return configName
    }

    fun patchFile(op: Action<SingleFilePatchSet>) {
        if (!singleFilePatches) {
            error("Single file patches not supported here")
        }
        val patchSet = objects.newInstance<SingleFilePatchSet>()
        op.execute(patchSet)
        patchSets.add(patchSet)
    }

    fun patchDir(op: Action<DirectoryPatchSet>) {
        val patchSet = objects.newInstance<DirectoryPatchSet>()
        op.execute(patchSet)
        patchSets.add(patchSet)
    }

    fun patchedDir(name: String): Provider<DirectoryPatchSet> =
        patchSets.map { it.filterIsInstance<DirectoryPatchSet>().single { s -> s.name.get() == name } }

    interface PatchSet

    abstract class SingleFilePatchSet : PatchSet {
        abstract val path: Property<String>
        abstract val outputFile: RegularFileProperty
        abstract val patchFile: RegularFileProperty
    }

    abstract class DirectoryPatchSet @Inject constructor(
        objects: ObjectFactory,
    ) : PatchSet {
        abstract val name: Property<String>
        abstract val upstreamPath: Property<String>
        abstract val upstreamDir: Property<DirectoryPatchSet>
        abstract val excludes: SetProperty<String>
        val repo: Property<Boolean> = objects.property<Boolean>().convention(false)

        abstract val outputDir: DirectoryProperty

        abstract val patchesDir: DirectoryProperty
        val rejectsDir: DirectoryProperty = objects.dirFrom(patchesDir, "rejected")
        val filePatchDir: DirectoryProperty = objects.dirFrom(patchesDir, "files")
        val featurePatchDir: DirectoryProperty = objects.dirFrom(patchesDir, "features")
    }
}
