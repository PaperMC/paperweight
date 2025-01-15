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
import org.gradle.api.file.BuildLayout
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory

abstract class PaperExtension @Inject constructor(objects: ObjectFactory, buildLayout: BuildLayout) {

    val rootDirectory: DirectoryProperty = objects.directoryProperty().convention(buildLayout.rootDirectory)
    val paperServerDir: DirectoryProperty = objects.dirFrom(rootDirectory, "paper-server")
    val serverPatchesDir: DirectoryProperty = objects.dirFrom(paperServerDir, "patches")
    val rejectsDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "rejected")
    val sourcePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "sources")
    val resourcePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "resources")
    val featurePatchDir: DirectoryProperty = objects.dirFrom(serverPatchesDir, "features")

    @Suppress("MemberVisibilityCanBePrivate")
    val buildDataDir: DirectoryProperty = objects.dirFrom(rootDirectory, "build-data")
    val devImports: RegularFileProperty = objects.fileFrom(buildDataDir, "dev-imports.txt")
    val additionalAts: RegularFileProperty = objects.fileFrom(buildDataDir, "paper.at")
    val reobfMappingsPatch: RegularFileProperty = objects.fileFrom(buildDataDir, "reobf-mappings-patch.tiny")
    val mappingsPatch: RegularFileProperty = objects.fileProperty()
}
