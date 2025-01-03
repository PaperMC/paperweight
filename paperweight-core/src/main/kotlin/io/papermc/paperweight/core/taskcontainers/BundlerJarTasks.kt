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

package io.papermc.paperweight.core.taskcontainers

import com.google.gson.JsonObject
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

@Suppress("MemberVisibilityCanBePrivate")
class BundlerJarTasks(
    project: Project,
    private val bundlerJarName: Provider<String>,
    private val mainClassString: Provider<String>,
    private val providers: ProviderFactory = project.providers,
) {
    val createBundlerJar: TaskProvider<CreateBundlerJar>
    val createPaperclipJar: TaskProvider<CreatePaperclipJar>

    val createReobfBundlerJar: TaskProvider<CreateBundlerJar>
    val createReobfPaperclipJar: TaskProvider<CreatePaperclipJar>

    init {
        val (createBundlerJar, createPaperclipJar) = project.createBundlerJarTask("mojmap")
        val (createReobfBundlerJar, createReobfPaperclipJar) = project.createBundlerJarTask("reobf")
        this.createBundlerJar = createBundlerJar
        this.createPaperclipJar = createPaperclipJar

        this.createReobfBundlerJar = createReobfBundlerJar
        createReobfBundlerJar { reobfRequiresDebug() }
        this.createReobfPaperclipJar = createReobfPaperclipJar
        createReobfPaperclipJar { reobfRequiresDebug() }
    }

    fun configureBundlerTasks(
        bundlerVersionJson: Provider<RegularFile>,
        serverLibrariesList: Provider<RegularFile>,
        vanillaJar: Provider<RegularFile>,
        mojangJar: Provider<RegularFile>,
        reobfJar: TaskProvider<RemapJar>,
        mcVersion: Provider<String>
    ) {
        createBundlerJar.configureWith(
            bundlerVersionJson,
            serverLibrariesList,
            vanillaJar,
            mojangJar,
        )
        createReobfBundlerJar.configureWith(
            bundlerVersionJson,
            serverLibrariesList,
            vanillaJar,
            reobfJar.flatMap { it.outputJar },
        )

        createPaperclipJar.configureWith(vanillaJar, createBundlerJar, mcVersion)
        createReobfPaperclipJar.configureWith(vanillaJar, createReobfBundlerJar, mcVersion)
    }

    private fun Project.createBundlerJarTask(
        classifier: String = "",
    ): Pair<TaskProvider<CreateBundlerJar>, TaskProvider<CreatePaperclipJar>> {
        val bundlerTaskName = "create${classifier.capitalized()}BundlerJar"
        val paperclipTaskName = "create${classifier.capitalized()}PaperclipJar"

        val bundlerJarTask = tasks.register<CreateBundlerJar>(bundlerTaskName) {
            group = "bundling"
            description = "Build a runnable bundler jar"

            paperclip.from(configurations.named(PAPERCLIP_CONFIG))
            mainClass.set(mainClassString)
            extraManifestMainAttributes.convention(mapOf("Enable-Native-Access" to "ALL-UNNAMED"))

            outputZip.set(layout.buildDirectory.file(jarName("bundler", classifier).map { "libs/$it" }))
        }
        val paperclipJarTask = tasks.register<CreatePaperclipJar>(paperclipTaskName) {
            group = "bundling"
            description = "Build a runnable paperclip jar"

            libraryChangesJson.set(bundlerJarTask.flatMap { it.libraryChangesJson })
            outputZip.set(layout.buildDirectory.file(jarName("paperclip", classifier).map { "libs/$it" }))
        }
        return bundlerJarTask to paperclipJarTask
    }

    private fun Project.jarName(kind: String, classifier: String): Provider<String> {
        return bundlerJarName.map {
            listOfNotNull(
                it,
                kind,
                project.version,
                classifier.takeIf { c -> c.isNotBlank() },
            ).joinToString("-") + ".jar"
        }
    }

    private fun TaskProvider<CreateBundlerJar>.configureWith(
        bundlerVersionJson: Provider<RegularFile>,
        serverLibrariesListFile: Provider<RegularFile>,
        vanillaJar: Provider<RegularFile>,
        serverJar: Provider<RegularFile>,
    ) = this {
        val runtimeClasspath = project.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
        val artifacts = runtimeClasspath.flatMap { config ->
            config.incoming.artifacts.resolvedArtifacts.map { a ->
                a.filter {
                    val id = it.id.componentIdentifier
                    id is ModuleComponentIdentifier || id is ProjectComponentIdentifier
                }.sortedBy { it.id.displayName }
            }
        }
        libraryArtifacts.set(
            artifacts.map { a ->
                a.map {
                    val obj = objects.newInstance(CreateBundlerJar.LibraryArtifact::class)
                    obj.id.set(it.id)
                    obj.path.set(it.file)
                    obj.variant.set(it.variant)
                    obj
                }
            }
        )
        libraryArtifactsFiles.from(runtimeClasspath)
        serverLibrariesList.set(serverLibrariesListFile)
        vanillaBundlerJar.set(vanillaJar)

        versionArtifacts {
            registerVersionArtifact(
                bundlerJarName.get(),
                bundlerVersionJson,
                providers,
                serverJar
            )
        }
    }

    private fun TaskProvider<CreatePaperclipJar>.configureWith(
        vanillaJar: Provider<RegularFile>,
        createBundlerJar: TaskProvider<CreateBundlerJar>,
        mcVers: Provider<String>
    ) = this {
        originalBundlerJar.set(vanillaJar)
        bundlerJar.set(createBundlerJar.flatMap { it.outputZip })
        mcVersion.set(mcVers)
    }

    companion object {
        fun NamedDomainObjectContainer<CreateBundlerJar.VersionArtifact>.registerVersionArtifact(
            name: String,
            versionJson: Provider<RegularFile>,
            providers: ProviderFactory,
            serverJar: Provider<RegularFile>
        ) = register(name) {
            id.set(providers.fileContents(versionJson).asText.map { gson.fromJson<JsonObject>(it)["id"].asString })
            file.set(serverJar)
        }
    }
}
