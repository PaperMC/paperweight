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

package io.papermc.paperweight.userdev

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.attribute.DevBundleOutput
import io.papermc.paperweight.attribute.MacheOutput
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.attribute.Obfuscation
import io.papermc.paperweight.userdev.internal.JunitExclusionRule
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.UserdevSetupTask
import io.papermc.paperweight.userdev.internal.util.cleanSharedCaches
import io.papermc.paperweight.userdev.internal.util.delayCleanupBy
import io.papermc.paperweight.userdev.internal.util.expireUnusedAfter
import io.papermc.paperweight.userdev.internal.util.genSources
import io.papermc.paperweight.userdev.internal.util.performCleanupAfter
import io.papermc.paperweight.userdev.internal.util.sharedCaches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.*
import org.gradle.util.internal.NameMatcher

abstract class PaperweightUser : Plugin<Project> {

    @get:Inject
    abstract val dependencyFactory: DependencyFactory

    @get:Inject
    abstract val buildEventsListenerRegistry: BuildEventsListenerRegistry

    @get:Inject
    abstract val javaToolchainService: JavaToolchainService

    @get:Inject
    abstract val problems: Problems

    override fun apply(target: Project) {
        target.plugins.apply("java")

        val sharedCacheRoot = target.gradle.gradleUserHomeDir.toPath().resolve("caches/paperweight-userdev")

        target.gradle.sharedServices.registerIfAbsent(DOWNLOAD_SERVICE_NAME, DownloadService::class) {
            parameters.projectPath.set(target.projectDir)
        }

        val cleanCache by target.tasks.registering<Delete> {
            group = GENERAL_TASK_GROUP
            description = "Delete the project-local paperweight-userdev setup cache."
            delete(target.layout.cache)
            delete(target.rootProject.layout.cache.resolve("paperweight-userdev"))
        }
        val cleanAll = target.tasks.register<Delete>("cleanAllPaperweightUserdevCaches") {
            group = GENERAL_TASK_GROUP
            description = "Delete the project-local & all shared paperweight-userdev setup caches."
            delete(sharedCacheRoot)
            dependsOn(cleanCache)
        }

        target.configurations.register(DEV_BUNDLE_CONFIG) {
            attributes.attribute(DevBundleOutput.ATTRIBUTE, target.objects.named(DevBundleOutput.ZIP))
        }

        // must not be initialized until afterEvaluate, as it resolves the dev bundle
        val userdevSetupProvider by lazy { createSetup(target) }
        val userdevSetup by lazy { userdevSetupProvider.get() }

        val userdev = target.extensions.create(
            PAPERWEIGHT_EXTENSION,
            PaperweightUserExtension::class,
            target.provider { userdevSetup },
            target.objects,
            target,
        )

        val setupTask = target.tasks.register("paperweightUserdevSetup", UserdevSetupTask::class) {
            group = GENERAL_TASK_GROUP
            launcher.set(userdev.javaLauncher)
        }

        target.dependencies.extensions.create(
            PAPERWEIGHT_EXTENSION,
            PaperweightUserDependenciesExtension::class,
            target.dependencies
        )

        createConfigurations(target, target.provider { userdevSetup }, setupTask)

        val reobfJar by target.tasks.registering<RemapJar> {
            group = GENERAL_TASK_GROUP
            description = "Remap the compiled plugin jar to Spigot's obfuscated runtime names."

            mappingsFile.set(setupTask.flatMap { it.reobfMappings })
            remapClasspath.from(setupTask.flatMap { it.mappedServerJar })
            toNamespace.set(SPIGOT_NAMESPACE)
            remapper.from(project.configurations.named(PLUGIN_REMAPPER_CONFIG))
        }

        target.configurations.register(REOBF_CONFIG) {
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
            userdev.javaLauncher.convention(javaToolchainService.defaultJavaLauncher(this))

            userdev.reobfArtifactConfiguration.get()
                .configure(this, reobfJar)

            decorateJarManifests()

            if (userdev.injectPaperRepository.get()) {
                repositories.maven(PAPER_MAVEN_REPO_URL) {
                    content { onlyForConfigurations(DEV_BUNDLE_CONFIG) }
                }
            }

            if (userdev.applyJunitExclusionRule.get()) {
                applyJunitExclusionRule()
            }

            if (isIDEASync()) {
                val startParameter = gradle.startParameter
                val taskRequests = startParameter.taskRequests.toMutableList()
                taskRequests.add(DefaultTaskExecutionRequest(listOf(setupTask.name)))
                startParameter.setTaskRequests(taskRequests)
            }

            // Print a friendly error message if the dev bundle is missing before we call anything else that will try and resolve it
            checkForDevBundle()

            configureRepositories(userdevSetup)

            setupTask.configure { setupService.set(userdevSetupProvider) }
            userdevSetup.afterEvaluate(createContext(this, setupTask))

            userdev.addServerDependencyTo.get().forEach {
                it.extendsFrom(configurations.getByName(MOJANG_MAPPED_SERVER_CONFIG))
            }

            // Clean v1 shared caches
            cleanSharedCaches(this, sharedCacheRoot)

            if (cleaningCache(cleanCache, cleanAll)) {
                tasks.withType(UserdevSetupTask::class).configureEach {
                    doFirst { throw PaperweightException("Cannot run setup tasks when cleaning caches") }
                }
            }
        }
    }

    private fun Project.cleaningCache(vararg cleanTasks: TaskProvider<*>): Boolean {
        val cleanTaskNames = cleanTasks.map { it.name }.toSet()

        // Manually check if cleanCache is a target, and skip setup.
        // Gradle moved NameMatcher to internal packages in 7.1, so this solution isn't ideal,
        // but it does work and allows using the cleanCache task without setting up the workspace first
        return gradle.startParameter.taskRequests
            .any { req ->
                req.args.any { arg ->
                    NameMatcher().find(arg, tasks.names) in cleanTaskNames
                }
            }
    }

    private fun Project.decorateJarManifests() {
        val op = Action<Jar> {
            manifest {
                attributes(MAPPINGS_NAMESPACE_MANIFEST_KEY to DEOBF_NAMESPACE)
            }
        }
        tasks.named("jar", Jar::class, op)
        if ("shadowJar" in tasks.names) {
            tasks.named("shadowJar", Jar::class, op)
        }
    }

    private fun Project.applyJunitExclusionRule() = dependencies {
        components {
            withModule<JunitExclusionRule>(JunitExclusionRule.TARGET)
        }
    }

    // Update the Paper repository endpoint for old dev bundles
    private fun String.fixRepoUrl(): String = replace("https://papermc.io/repo/", "https://repo.papermc.io/")

    private fun Project.configureRepositories(userdevSetup: UserdevSetup) = repositories {
        userdevSetup.mache?.url?.let {
            maven(it) {
                name = MACHE_REPO_NAME
                content { onlyForConfigurations(MACHE_CONFIG) }
            }
        }
        userdevSetup.paramMappings?.url?.let {
            maven(it.fixRepoUrl()) {
                name = PARAM_MAPPINGS_REPO_NAME
                content { onlyForConfigurations(PARAM_MAPPINGS_CONFIG) }
            }
        }
        userdevSetup.remapper?.url?.let {
            maven(it.fixRepoUrl()) {
                name = REMAPPER_REPO_NAME
                content { onlyForConfigurations(REMAPPER_CONFIG) }
            }
        }
        maven(PAPER_MAVEN_REPO_URL) {
            name = PLUGIN_REMAPPER_REPO_NAME
            content { onlyForConfigurations(PLUGIN_REMAPPER_CONFIG) }
        }
        userdevSetup.decompiler?.url?.let {
            maven(it.fixRepoUrl()) {
                name = DECOMPILER_REPO_NAME
                content { onlyForConfigurations(DECOMPILER_CONFIG) }
            }
        }
        for (repo in userdevSetup.libraryRepositories) {
            maven(repo.fixRepoUrl())
        }
    }

    private fun Project.checkForDevBundle() {
        val hasDevBundle = runCatching {
            !configurations.getByName(DEV_BUNDLE_CONFIG).isEmpty
        }
        if (hasDevBundle.isFailure || !hasDevBundle.getOrThrow()) {
            val message = "Unable to resolve a dev bundle, which is required for paperweight to function."
            val ex = PaperweightException(message, hasDevBundle.exceptionOrNull())
            throw problems.reporter.throwing {
                severity(Severity.ERROR)
                id("paperweight-userdev-cannot-resolve-dev-bundle", message)
                solution(
                    "Add a dev bundle to the 'paperweightDevelopmentBundle' configuration (the dependencies.paperweight extension can" +
                        " help with this), and ensure there is a repository to resolve it from (the Paper repository is used by default)."
                )
                withException(ex)
            }
        }
    }

    private fun Configuration.dependenciesFrom(
        transitive: (String) -> Boolean = { true },
        supplier: () -> MavenDep?
    ) {
        defaultDependencies {
            val deps = supplier()?.coordinates ?: emptyList()
            for (dep in deps) {
                val dependency = dependencyFactory.create(dep)
                dependency.isTransitive = transitive(dep)
                add(dependency)
            }
        }
    }

    private fun createConfigurations(
        target: Project,
        userdevSetup: Provider<UserdevSetup>,
        setupTask: TaskProvider<UserdevSetupTask>,
    ) {
        target.configurations.register(MACHE_CONFIG) {
            dependenciesFrom { userdevSetup.get().mache }
            attributes.attribute(MacheOutput.ATTRIBUTE, target.objects.named(MacheOutput.ZIP))
        }
        target.configurations.register(DECOMPILER_CONFIG) {
            dependenciesFrom { userdevSetup.get().decompiler }
        }
        target.configurations.register(PARAM_MAPPINGS_CONFIG) {
            dependenciesFrom { userdevSetup.get().paramMappings }
        }

        target.configurations.register(REMAPPER_CONFIG) {
            // when using a fat jar for tiny-remapper we don't need its transitive deps
            dependenciesFrom({ !it.contains(":tiny-remapper:") || !it.endsWith(":fat") }) {
                userdevSetup.get().remapper
            }
        }
        target.configurations.register(PLUGIN_REMAPPER_CONFIG) {
            // when using a fat jar for tiny-remapper we don't need its transitive deps
            dependenciesFrom({ !it.contains(":tiny-remapper:") || !it.endsWith(":fat") }) {
                MavenDep(
                    PAPER_MAVEN_REPO_URL,
                    listOf("${listOf("net", "fabricmc").joinToString(".")}:tiny-remapper:${LibraryVersions.TINY_REMAPPER}:fat")
                )
            }
        }

        target.configurations.register(MOJANG_MAPPED_SERVER_CONFIG) {
            defaultDependencies {
                userdevSetup.get()
                    .populateCompileConfiguration(createContext(target, setupTask), this)
            }
        }

        target.configurations.register(MOJANG_MAPPED_SERVER_RUNTIME_CONFIG) {
            defaultDependencies {
                userdevSetup.get()
                    .populateRuntimeConfiguration(createContext(target, setupTask), this)
            }
        }
    }

    private fun createContext(project: Project, setupTask: TaskProvider<UserdevSetupTask>): SetupHandler.ConfigurationContext =
        SetupHandler.ConfigurationContext(project, dependencyFactory, javaToolchainService, setupTask)

    private fun createSetup(target: Project): Provider<UserdevSetup> {
        val bundleConfig = target.configurations.named(DEV_BUNDLE_CONFIG)
        val devBundleZip = bundleConfig.map { it.singleFile }.convertToPath()
        val bundleHash = devBundleZip.sha256asHex()
        val cacheDir = if (!target.sharedCaches) {
            target.rootProject.layout.cache.resolve("paperweight-userdev/v2/work")
        } else {
            target.gradle.gradleUserHomeDir.toPath().resolve("caches/paperweight-userdev/v2/work")
        }

        val serviceName = "paperweight-userdev:setupService:$bundleHash"
        val ret = target.gradle.sharedServices.registerIfAbsent(serviceName, UserdevSetup::class) {
            parameters {
                cache.set(cacheDir)
                downloadService.set(target.download)
                genSources.set(target.genSources)

                bundleZip.set(devBundleZip)
                bundleZipHash.set(bundleHash)

                expireUnusedAfter.set(expireUnusedAfter(target))
                performCleanupAfter.set(performCleanupAfter(target))
                delayCleanupBy.set(delayCleanupBy(target))
            }
        }
        buildEventsListenerRegistry.onTaskCompletion(ret)
        return ret
    }
}
