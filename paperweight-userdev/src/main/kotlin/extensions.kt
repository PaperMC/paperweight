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

import io.papermc.paperweight.util.constants.*
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.kotlin.dsl.*

/**
 * Adds a dependency on Paper's dev bundle to the dev bundle [org.gradle.api.artifacts.Configuration].
 *
 * @param version dependency version
 * @param group dependency group
 * @param artifactId dependency artifactId
 * @param configuration dependency configuration
 * @param classifier dependency classifier
 * @param ext dependency extension
 * @param devBundleConfigurationName name of the dev bundle [org.gradle.api.artifacts.Configuration]
 * @param configurationAction action configuring the dependency
 */
@Deprecated(
    message = "Replaced by extension methods",
    replaceWith = ReplaceWith(
        "paperweight.paperDevBundle"
    )
)
fun DependencyHandlerScope.paperDevBundle(
    version: String? = null,
    group: String = "io.papermc.paper",
    artifactId: String = "dev-bundle",
    configuration: String? = null,
    classifier: String? = null,
    ext: String? = null,
    devBundleConfigurationName: String = DEV_BUNDLE_CONFIG,
    configurationAction: ExternalModuleDependency.() -> Unit = {}
): ExternalModuleDependency = devBundleConfigurationName(group, artifactId, version, configuration, classifier, ext, configurationAction)

/**
 * Adds a dependency to the dev bundle [org.gradle.api.artifacts.Configuration].
 *
 * @param group dependency group
 * @param version dependency version
 * @param artifactId dependency artifactId
 * @param configuration dependency configuration
 * @param classifier dependency classifier
 * @param ext dependency extension
 * @param devBundleConfigurationName name of the dev bundle [org.gradle.api.artifacts.Configuration]
 * @param configurationAction action configuring the dependency
 */
@Deprecated(
    message = "Replaced by extension methods",
    replaceWith = ReplaceWith(
        "paperweight.devBundle"
    )
)
fun DependencyHandlerScope.paperweightDevBundle(
    group: String,
    version: String? = null,
    artifactId: String = "dev-bundle",
    configuration: String? = null,
    classifier: String? = null,
    ext: String? = null,
    devBundleConfigurationName: String = DEV_BUNDLE_CONFIG,
    configurationAction: ExternalModuleDependency.() -> Unit = {}
): ExternalModuleDependency = devBundleConfigurationName(group, artifactId, version, configuration, classifier, ext, configurationAction)
