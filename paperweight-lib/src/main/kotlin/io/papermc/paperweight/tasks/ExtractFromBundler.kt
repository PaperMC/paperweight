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

package io.papermc.paperweight.tasks

import com.google.gson.JsonObject
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class ExtractFromBundler : BaseTask() {

    @get:Classpath
    abstract val bundlerJar: RegularFileProperty

    @get:OutputFile
    abstract val serverJar: RegularFileProperty

    @get:OutputFile
    abstract val serverLibrariesTxt: RegularFileProperty

    @get:OutputDirectory
    abstract val serverLibraryJars: DirectoryProperty

    @get:OutputFile
    abstract val versionJson: RegularFileProperty

    @get:OutputFile
    abstract val serverLibrariesList: RegularFileProperty

    @get:OutputFile
    abstract val serverVersionsList: RegularFileProperty

    @TaskAction
    fun run() {
        ServerBundler.extractFromBundler(
            bundlerJar.path,
            serverJar.path,
            serverLibraryJars.path,
            serverLibrariesTxt.path,
            serverLibrariesList.path,
            serverVersionsList.path,
            versionJson.path
        )
    }
}

object ServerBundler {
    fun extractFromBundler(
        bundlerJar: Path,
        serverJar: Path,
        serverLibraryJars: Path,
        serverLibrariesTxt: Path?,
        serverLibrariesList: Path?,
        serverVersionsList: Path?,
        versionJson: Path?
    ) {
        bundlerJar.openZip().use { bundlerFs ->
            val root = bundlerFs.rootDirectories.first()
            extractServerJar(root, serverJar, versionJson)
            extractLibraryJars(root, serverLibraryJars)
            serverLibrariesTxt?.let { writeLibrariesTxt(root, it) }
            serverLibrariesList?.let { root.resolve(FileEntry.LIBRARIES_LIST).copyTo(it, overwrite = true) }
            serverVersionsList?.let { root.resolve(FileEntry.VERSIONS_LIST).copyTo(it, overwrite = true) }
        }
    }

    private fun extractServerJar(bundlerZip: Path, serverJar: Path, outputVersionJson: Path?) {
        val serverVersionJson = bundlerZip.resolve(FileEntry.VERSION_JSON)
        outputVersionJson?.let { output ->
            serverVersionJson.copyTo(output, overwrite = true)
        }

        val versionId = gson.fromJson<JsonObject>(serverVersionJson)["id"].asString
        val versions = bundlerZip.resolve(FileEntry.VERSIONS_LIST).readLines()
            .map { it.split('\t') }
            .associate { it[1] to it[2] }
        val serverJarPath = bundlerZip.resolve("${FileEntry.VERSIONS_DIR}/${versions[versionId]}")

        serverJar.parent.createDirectories()
        serverJarPath.copyTo(serverJar, overwrite = true)
    }

    private fun extractLibraryJars(bundlerZip: Path, serverLibraryJars: Path) {
        serverLibraryJars.deleteRecursive()
        serverLibraryJars.parent.createDirectories()
        bundlerZip.resolve(FileEntry.LIBRARIES_DIR).copyRecursivelyTo(serverLibraryJars)
    }

    private fun writeLibrariesTxt(bundlerZip: Path, serverLibrariesTxt: Path) {
        val libs = bundlerZip.resolve(FileEntry.LIBRARIES_LIST).readLines()
            .map { it.split('\t')[1] }

        serverLibrariesTxt.parent.createDirectories()
        serverLibrariesTxt.writeLines(libs)
    }
}
