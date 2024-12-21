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

package io.papermc.paperweight.patcher.extension

import io.papermc.paperweight.core.extension.UpstreamConfig
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.*

@Suppress("unused", "MemberVisibilityCanBePrivate")
abstract class PaperweightPatcherExtension @Inject constructor(private val objects: ObjectFactory) {

    val upstreams: NamedDomainObjectContainer<UpstreamConfig> = objects.domainObjectContainer(UpstreamConfig::class) {
        objects.newInstance(it, true)
    }

    fun NamedDomainObjectContainer<UpstreamConfig>.paper(
        op: Action<UpstreamConfig>
    ): NamedDomainObjectProvider<UpstreamConfig> = register("paper") {
        repo.convention(github("PaperMC", "Paper"))
        applyUpstreamNested.convention(false)
        op.execute(this)
    }
}
