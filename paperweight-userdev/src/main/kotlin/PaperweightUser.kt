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

package io.papermc.paperweight.userdev

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.attribute.Obfuscation
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.util.genSources
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.util.internal.NameMatcher
import org.gradle.workers.WorkerExecutor

abstract class PaperweightUser : Plugin<Project> {
    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Inject
    abstract val javaToolchainService: JavaToolchainService

    override fun apply(target: Project) {
        target.gradle.sharedServices.registerIfAbsent("download", DownloadService::class) {}

        val cleanCache by target.tasks.registering<Delete> {
            group = "paperweight"
            description = "Delete the project setup cache and task outputs."
            delete(target.layout.cache)
        }

        target.configurations.create(DEV_BUNDLE_CONFIG)

        // these must not be initialized until afterEvaluate, as they resolve the dev bundle
        val userdevSetup by lazy {
            val devBundleZip = target.configurations.named(DEV_BUNDLE_CONFIG).map { it.singleFile }.convertToPath()
            val serviceName = "paperweight-userdev:setupService:${devBundleZip.sha256asHex()}"

            target.gradle.sharedServices
                .registerIfAbsent(serviceName, UserdevSetup::class) {
                    parameters {
                        cache.set(target.layout.cache)
                        bundleZip.set(devBundleZip)
                        downloadService.set(target.download)
                        genSources.set(target.genSources)
                    }
                }
                .get()
        }

        val userdev = target.extensions.create(
            PAPERWEIGHT_EXTENSION,
            PaperweightUserExtension::class,
            target,
            workerExecutor,
            javaToolchainService,
            target.provider { userdevSetup },
            target.objects
        )

        createConfigurations(target, target.provider { userdevSetup })

        val reobfJar by target.tasks.registering<RemapJar> {
            group = "paperweight"
            description = "Remap the compiled plugin jar to Spigot's obfuscated runtime names."

            mappingsFile.pathProvider(target.provider { userdevSetup.reobfMappings })
            remapClasspath.from(target.provider { userdevSetup.serverJar })

            fromNamespace.set(DEOBF_NAMESPACE)
            toNamespace.set(SPIGOT_NAMESPACE)

            remapper.from(project.configurations.named(REMAPPER_CONFIG))
            remapperArgs.set(target.provider { userdevSetup.pluginRemapArgs })
        }

        target.configurations.create(REOBF_CONFIG) {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, target.objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, target.objects.named(LibraryElements.JAR))
                attribute(Bundling.BUNDLING_ATTRIBUTE, target.objects.named(Bundling.EXTERNAL))
                attribute(Obfuscation.OBFUSCATION_ATTRIBUTE, target.objects.named(Obfuscation.OBFUSCATED))
            }
            outgoing.artifact(reobfJar)
        }

        target.afterEvaluate {
            // Manually check if cleanCache is a target, and skip setup.
            // Gradle moved NameMatcher to internal packages in 7.1, so this solution isn't ideal,
            // but it does work and allows using the cleanCache task without setting up the workspace first
            val cleaningCache = gradle.startParameter.taskRequests
                .any { req ->
                    req.args.any { arg ->
                        NameMatcher().find(arg, tasks.names) == cleanCache.name
                    }
                }
            if (cleaningCache) {
                return@afterEvaluate
            }

            val jar = tasks.named<AbstractArchiveTask>("jar") {
                archiveClassifier.set("dev")
            }

            val devJarTask = if (tasks.findByName("shadowJar") != null) {
                tasks.named<AbstractArchiveTask>("shadowJar") {
                    archiveClassifier.set("dev-all")
                }
            } else {
                jar
            }

            val archivesName = target.extensions.findByType(BasePluginExtension::class)?.archivesName
                ?: target.provider { target.name }

            reobfJar {
                inputJar.set(devJarTask.flatMap { it.archiveFile })
                outputJar.convention(archivesName.flatMap { layout.buildDirectory.file("libs/$it-${project.version}.jar") })
            }

            if (userdev.injectPaperRepository.get()) {
                target.repositories.maven(PAPER_MAVEN_REPO_URL) {
                    content { onlyForConfigurations(DEV_BUNDLE_CONFIG) }
                }
            }

            // Print a friendly error message if the dev bundle is missing before we call anything else that will try and resolve it
            checkForDevBundle()

            configureRepositories(userdevSetup)
            ideaFixes(target)
        }
    }

    private fun Project.configureRepositories(userdevSetup: UserdevSetup) = repositories {
        // Initial repos
        val before = toList()

        // Add local repos
        userdevSetup.addLocalRepositories(project)

        // Move local repos to front
        val new = toList().subtract(before.toSet())
        new.forEach {
            remove(it)
            addFirst(it)
        }

        maven(userdevSetup.paramMappings.url) {
            name = PARAM_MAPPINGS_REPO_NAME
            content { onlyForConfigurations(PARAM_MAPPINGS_CONFIG) }
        }
        maven(userdevSetup.remapper.url) {
            name = REMAPPER_REPO_NAME
            content { onlyForConfigurations(REMAPPER_CONFIG) }
        }
        maven(userdevSetup.decompiler.url) {
            name = DECOMPILER_REPO_NAME
            content { onlyForConfigurations(DECOMPILER_CONFIG) }
        }
        for (repo in userdevSetup.libraryRepositories) {
            maven(repo)
        }
    }

    private fun Project.checkForDevBundle() {
        val hasDevBundle = runCatching {
            !configurations.getByName(DEV_BUNDLE_CONFIG).isEmpty
        }
        if (hasDevBundle.isFailure || !hasDevBundle.getOrThrow()) {
            val message = "paperweight requires a development bundle to be added to the 'paperweightDevelopmentBundle' configuration, as" +
                " well as a repository to resolve it from in order to function. Use the paperweightDevBundle extension function to do this easily."
            throw PaperweightException(
                message,
                hasDevBundle.exceptionOrNull()?.let { PaperweightException("Failed to resolve dev bundle", it) }
            )
        }
    }

    private fun createConfigurations(
        target: Project,
        userdevSetup: Provider<UserdevSetup>
    ) {
        target.configurations.create(DECOMPILER_CONFIG) {
            defaultDependencies {
                for (dep in userdevSetup.get().decompiler.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.create(PARAM_MAPPINGS_CONFIG) {
            defaultDependencies {
                for (dep in userdevSetup.get().paramMappings.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        target.configurations.create(REMAPPER_CONFIG) {
            isTransitive = false // we use a fat jar for tiny-remapper, so we don't need it's transitive deps
            defaultDependencies {
                for (dep in userdevSetup.get().remapper.coordinates) {
                    add(target.dependencies.create(dep))
                }
            }
        }

        val mojangMappedServerConfig = target.configurations.create(MOJANG_MAPPED_SERVER_CONFIG) {
            exclude("junit", "junit") // json-simple exposes junit for some reason
            defaultDependencies {
                val ctx = createContext(target)
                userdevSetup.get().let { setup ->
                    setup.createOrUpdateLocalRepositories(ctx)
                    setup.populateCompileConfiguration(ctx, this)
                }
            }
        }

        target.plugins.withType<JavaPlugin> {
            listOf(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME
            ).map(target.configurations::named).forEach { config ->
                config {
                    extendsFrom(mojangMappedServerConfig)
                }
            }
        }

        target.configurations.create(MOJANG_MAPPED_SERVER_RUNTIME_CONFIG) {
            defaultDependencies {
                val ctx = createContext(target)
                userdevSetup.get().let { setup ->
                    setup.createOrUpdateLocalRepositories(ctx)
                    setup.populateRuntimeConfiguration(ctx, this)
                }
            }
        }
    }

    private fun isIdeaSync(): Boolean =
        java.lang.Boolean.parseBoolean(System.getProperty("idea.sync.active", "false"))

    /**
     * IDEA's DownloadSources task resolves sources weirdly, by resolving the compileClasspath once before it runs,
     * gradle will already know where to look regardless. Also do this on syncs in general to attempt to allow automatic
     * source downloads.
     */
    private fun ideaFixes(target: Project) {
        val paperweightUserdevPrimeForIdea by target.tasks.registering<DefaultTask> {
            inputs.files(target.configurations.named("compileClasspath"))
            description = "Internal paperweight-userdev task ran before IntelliJ IDEA's DownloadSources task"
            doLast {
            }
        }
        target.tasks.all {
            if (name == "DownloadSources") {
                dependsOn(paperweightUserdevPrimeForIdea)
            }
        }

        if (isIdeaSync()) {
            val startParameter = target.gradle.startParameter
            val taskRequests = ArrayList(startParameter.taskRequests)

            taskRequests.add(DefaultTaskExecutionRequest(listOf(paperweightUserdevPrimeForIdea.name)))
            startParameter.setTaskRequests(taskRequests)
        }
    }

    private fun createContext(project: Project): SetupHandler.Context =
        SetupHandler.Context(project, workerExecutor, javaToolchainService)
}
