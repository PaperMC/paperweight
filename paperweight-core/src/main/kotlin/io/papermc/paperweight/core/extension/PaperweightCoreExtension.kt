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
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.*

open class PaperweightCoreExtension(objects: ObjectFactory, layout: ProjectLayout) {

    val minecraftVersion: Property<String> = objects.property()
    val minecraftManifestUrl: Property<String> = objects.property<String>().convention(MC_MANIFEST_URL)

    val mainClass: Property<String> = objects.property<String>().convention("org.bukkit.craftbukkit.Main")
    val bundlerJarName: Property<String> = objects.property<String>().convention("paper")

    val macheRepo: Property<String> = objects.property<String>().convention(PAPER_MAVEN_REPO_URL)

    val macheOldPath: DirectoryProperty = objects.directoryProperty()
    val gitFilePatches: Property<Boolean> = objects.property<Boolean>().convention(false)

    val vanillaJarIncludes: ListProperty<String> = objects.listProperty<String>().convention(
        listOf("/*.class", "/net/minecraft/**", "/com/mojang/math/**")
    )

    val reobfPackagesToFix: ListProperty<String> = objects.listProperty()

    val spigot = SpigotExtension(objects)
    val paper = PaperExtension(objects, layout)

    @Suppress("unused")
    fun spigot(action: Action<in SpigotExtension>) {
        action.execute(spigot)
    }

    @Suppress("unused")
    fun paper(action: Action<in PaperExtension>) {
        action.execute(paper)
    }

    val forks: NamedDomainObjectContainer<ForkConfig> = objects.domainObjectContainer(ForkConfig::class)

    val activeFork: Property<ForkConfig> = objects.property()
}
