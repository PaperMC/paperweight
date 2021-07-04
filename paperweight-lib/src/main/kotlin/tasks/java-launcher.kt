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

import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Nested
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.*

private fun JavaLauncherTaskBase.defaultJavaLauncher(project: Project): Provider<JavaLauncher> {
    return javaToolchainService.launcherFor(project.extensions.getByType<JavaPluginExtension>().toolchain)
        .orElse(
            javaToolchainService.launcherFor {
                // If the java plugin isn't applied, or no toolchain value was set
                languageVersion.set(JavaLanguageVersion.of(16))
                vendor.set(JvmVendorSpec.ADOPTOPENJDK)
            }
        )
}

interface JavaLauncherTaskBase {
    @get:Nested
    val launcher: Property<JavaLauncher>

    @get:Inject
    val javaToolchainService: JavaToolchainService
}

abstract class JavaLauncherTask : BaseTask(), JavaLauncherTaskBase {

    override fun init() {
        super.init()

        launcher.convention(defaultJavaLauncher(project))
    }
}

abstract class JavaLauncherZippedTask : ZippedTask(), JavaLauncherTaskBase {

    override fun init() {
        super.init()

        launcher.convention(defaultJavaLauncher(project))
    }
}
