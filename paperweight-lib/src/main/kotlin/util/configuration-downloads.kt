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

package io.papermc.paperweight.util

import io.papermc.paperweight.util.constants.*
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*

fun Project.resolveWithRepos(
    deps: Provider<List<String>>,
    repos: Provider<List<String>>,
    downloadName: String,
    configurationConfig: Configuration.() -> Unit = {}
): Provider<Configuration> =
    deps.zip(repos) { depCoords, repoList ->
        resolveWithRepos(depCoords, repoList, downloadName, configurationConfig)
    }

fun Project.resolveWithRepos(
    deps: List<String>,
    repos: List<String>,
    downloadName: String,
    configurationConfig: Configuration.() -> Unit = {}
): Configuration {
    val configName = downloadConfigName(downloadName)
    val config = configurations.findByName(configName) ?: configurations.create(configName) {
        configurationConfig()
        withDependencies {
            deps.map { project.dependencies.create(it) }
                .forEach { dep -> add(dep) }
        }
    }

    val added = repos.map { repo ->
        repositories.maven(repo) {
            name = "$repo for $configName"
            content { onlyForConfigurations(configName) }
        }
    }

    try {
        return config.also { it.resolvedConfiguration }
    } finally {
        repositories.removeAll(added)
    }
}

fun Project.downloadFile(fileUrl: Provider<String>, downloadName: String): Provider<RegularFile> =
    fileUrl.flatMap { downloadFile(it, downloadName) }

fun Project.downloadFile(url: String, downloadName: String): Provider<RegularFile> =
    layout.file(provider { downloadFileNow(url, downloadName).toFile() })

fun Project.downloadFileNow(
    url: String,
    downloadName: String
): Path {
    val urlHash = toHex(url.byteInputStream().hash(digestSha256()))
    val dependencyNotation = "$PAPERWEIGHT_DOWNLOAD:$downloadName:$urlHash"

    val configName = downloadConfigName(downloadName)
    val config = configurations.findByName(configName) ?: configurations.create(configName) {
        withDependencies {
            add(
                project.dependencies.create(dependencyNotation).also {
                    (it as ExternalModuleDependency).isChanging = true
                }
            )
        }
    }

    val ivy = repositories.ivy {
        name = "$url for $configName"
        artifactPattern(url)
        metadataSources { artifact() }
        content {
            onlyForConfigurations(configName)
            includeFromDependencyNotation(dependencyNotation)
        }
    }

    try {
        return config.singleFile.toPath()
    } finally {
        repositories.remove(ivy)
    }
}
