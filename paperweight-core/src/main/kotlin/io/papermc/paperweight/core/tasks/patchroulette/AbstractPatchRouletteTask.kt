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

import io.papermc.paperweight.core.util.CloudflareAccessManagedOAuthClient
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.set
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Patch Roulette tasks operate on remote resources and should always run when requested.")
abstract class AbstractPatchRouletteTask : BaseTask() {
    @get:Input
    abstract val endpoint: Property<String>

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Internal
    abstract val oauthCacheDirectory: DirectoryProperty

    override fun init() {
        super.init()
        endpoint.convention("https://patch-roulette.papermc.io/api")
        oauthCacheDirectory.set(
            project.gradle.gradleUserHomeDir.resolve("$CACHE_PATH/$PATCH_ROULETTE_OAUTH_CACHE_DIR")
        )
    }

    abstract fun run(api: PatchRouletteApi)

    @TaskAction
    fun runInternal() {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val oauthClient = CloudflareAccessManagedOAuthClient(
            client,
            URI.create(endpoint.get()),
            oauthCacheDirectory.path,
        )
        run(PatchRouletteApi(client, endpoint.get(), minecraftVersion.get(), oauthClient::accessToken))
    }
}
