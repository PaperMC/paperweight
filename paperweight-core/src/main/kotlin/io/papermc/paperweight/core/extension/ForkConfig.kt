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
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.*

abstract class ForkConfig @Inject constructor(
    private val configName: String,
    providers: ProviderFactory,
    objects: ObjectFactory,
    layout: ProjectLayout,
) : Named {
    override fun getName(): String {
        return configName
    }

    val rootDirectory: DirectoryProperty = objects.directoryProperty().convention(layout.projectDirectory.dir("../"))
    val serverDirectory: DirectoryProperty = objects.dirFrom(rootDirectory, providers.provider { "$name-server" })
    val serverPatchesDir: DirectoryProperty = objects.dirFrom(serverDirectory, "minecraft-patches")
    val rejectsDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "rejected")
    val sourcePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "sources")
    val resourcePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "resources")
    val featurePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "features")

    val buildDataDir: DirectoryProperty = objects.dirFrom(rootDirectory, "build-data")
    val devImports: RegularFileProperty = objects.fileFrom(buildDataDir, "dev-imports.txt")
    val additionalAts: RegularFileProperty = objects.fileFrom(buildDataDir, providers.provider { "$name.at" })
    val reobfMappingsPatch: RegularFileProperty = objects.fileFrom(buildDataDir, "reobf-mappings-patch.tiny")

    val forks: Property<ForkConfig> = objects.property()
    val forksPaper: Property<Boolean> = objects.property<Boolean>().convention(forks.map { false }.orElse(true))

    private val upstreamProvider: Provider<UpstreamConfig> = forks.map<UpstreamConfig> {
        objects.newInstance(it.name, false)
    }.orElse(
        providers.provider { objects.newInstance("paper", false) }
    )

    val upstream: UpstreamConfig by lazy {
        upstreamProvider.get()
    }

    fun upstream(op: Action<UpstreamConfig>) {
        op.execute(upstream)
    }
}
