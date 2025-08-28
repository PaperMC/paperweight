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

import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.*

abstract class PaperweightCoreExtension @Inject constructor(objects: ObjectFactory, project: Project) {
    val minecraftVersion: Property<String> = objects.property()
    val minecraftManifestUrl: Property<String> = objects.property<String>().convention(MC_MANIFEST_URL)

    val mainClass: Property<String> = objects.property<String>().convention("org.bukkit.craftbukkit.Main")
    val bundlerJarName: Property<String> = objects.property<String>().convention("paper")

    val macheRepo: Property<String> = objects.property<String>().convention(PAPER_MAVEN_REPO_URL)

    val gitFilePatches: Property<Boolean> = objects.property<Boolean>().convention(false)
    val filterPatches: Property<Boolean> = objects.property<Boolean>().convention(true)

    val vanillaJarIncludes: ListProperty<String> = objects.listProperty<String>().convention(
        listOf("/*.class", "/net/minecraft/**", "/com/mojang/math/**")
    )

    val reobfPackagesToFix: ListProperty<String> = objects.listProperty()

    val spigot = objects.newInstance<SpigotExtension>().also { spigot ->
        spigot.enabled.convention(
            spigot.buildDataRef.zip(spigot.packageVersion) { ref, pkg -> ref.isNotBlank() && pkg.isNotBlank() }
                .orElse(false)
        )
    }
    val paper = objects.newInstance<PaperExtension>(project)

    @Suppress("unused")
    fun spigot(action: Action<in SpigotExtension>) {
        action.execute(spigot)
    }

    @Suppress("unused")
    fun paper(action: Action<in PaperExtension>) {
        action.execute(paper)
    }

    val forks: NamedDomainObjectContainer<ForkConfig> = objects.domainObjectContainer(ForkConfig::class) {
        objects.newInstance<ForkConfig>(it)
    }

    val activeFork: Property<ForkConfig> = objects.property()

    val updatingMinecraft = objects.newInstance<UpdatingMinecraftExtension>()

    fun updatingMinecraft(action: Action<UpdatingMinecraftExtension>) {
        action.execute(updatingMinecraft)
    }
}
