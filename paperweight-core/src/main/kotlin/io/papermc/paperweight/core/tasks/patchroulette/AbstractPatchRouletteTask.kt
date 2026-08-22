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

package io.papermc.paperweight.core.tasks.patchroulette

import io.papermc.paperweight.core.util.OAuthClient
import io.papermc.paperweight.tasks.*
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class AbstractPatchRouletteTask : BaseTask() {
    @get:Input
    abstract val host: Property<String>

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Internal
    abstract val oauthCacheDirectory: DirectoryProperty

    override fun init() {
        super.init()
        host.convention("https://patch-roulette.papermc.io")
        oauthCacheDirectory.fileValue(project.gradle.gradleUserHomeDir.resolve("caches/paperweight/patch-roulette/oauth"))
        doNotTrackState("Run when requested")
    }

    abstract fun run(api: PatchRouletteApi)

    @TaskAction
    fun runInternal() {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        try {
            val accessToken = OAuthClient(
                client,
                URI.create(host.get()),
                oauthCacheDirectory.get().asFile.toPath(),
                logger,
            ).accessToken()
            run(PatchRouletteApi(client, host.get(), accessToken, minecraftVersion.get()))
        } finally {
            client.close()
        }
    }
}
