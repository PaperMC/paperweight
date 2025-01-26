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

import com.github.salomonbrys.kotson.typeToken
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class AbstractPatchRouletteTask : BaseTask() {
    @get:Inject
    abstract val providers: ProviderFactory

    @get:Input
    abstract val endpoint: Property<String>

    @get:Input
    abstract val authToken: Property<String>

    @get:Input
    abstract val minecraftVersion: Property<String>

    private var httpClient: HttpClient? = null

    override fun init() {
        super.init()
        endpoint.convention("https://patch-roulette.papermc.io")
        authToken.convention(providers.gradleProperty("paperweight.patch-roulette-token"))
        doNotTrackState("Run when requested")
    }

    abstract fun run()

    @TaskAction
    fun runInternal() {
        httpClient = HttpClient.newHttpClient()
        try {
            run()
        } finally {
            httpClient?.let { client ->
                runCatching { client::class.java.getMethod("close").invoke(client) }
            }
            httpClient = null
        }
    }

    private fun httpClient() = requireNotNull(httpClient)

    private fun HttpRequest.Builder.auth() = header("Authorization", "Basic " + authToken.get())
    private fun HttpRequest.Builder.contentTypeTextPlain() = header("Content-Type", "text/plain")
    private fun HttpRequest.Builder.contentTypeApplicationJson() = header("Content-Type", "application/json")

    @Internal
    fun getAvailablePatches(): List<String> {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(minecraftVersion.get()))
                .uri(URI.create(endpoint.get() + "/get-available-patches"))
                .auth()
                .contentTypeTextPlain()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        return gson.fromJson(response.body(), typeToken<List<String>>())
    }

    enum class Status {
        WIP,
        DONE,
        AVAILABLE
    }

    data class Patch(val path: String, val status: Status, val responsibleUser: String?)

    @Internal
    fun getAllPatches(): List<Patch> {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(minecraftVersion.get()))
                .uri(URI.create(endpoint.get() + "/get-all-patches"))
                .auth()
                .contentTypeTextPlain()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        return gson.fromJson(response.body(), typeToken<List<Patch>>())
    }

    data class SetPatches(val paths: List<String>, val minecraftVersion: String)

    fun setPatches(paths: List<String>) {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(SetPatches(paths, minecraftVersion.get()))))
                .uri(URI.create(endpoint.get() + "/set-patches"))
                .auth()
                .contentTypeApplicationJson()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        logger.lifecycle("Set patches for ${minecraftVersion.get()}")
    }

    fun clearPatches() {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(minecraftVersion.get()))
                .uri(URI.create(endpoint.get() + "/clear-patches"))
                .auth()
                .contentTypeTextPlain()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        logger.lifecycle("Cleared patches for ${minecraftVersion.get()}")
    }

    data class PatchInfo(val path: String, val minecraftVersion: String)

    fun startPatch(path: String) {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(PatchInfo(path, minecraftVersion.get()))))
                .uri(URI.create(endpoint.get() + "/start-patch"))
                .auth()
                .contentTypeApplicationJson()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        logger.lifecycle("Started patch $path")
    }

    fun completePatch(path: String) {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(PatchInfo(path, minecraftVersion.get()))))
                .uri(URI.create(endpoint.get() + "/complete-patch"))
                .auth()
                .contentTypeApplicationJson()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        logger.lifecycle("Completed patch $path")
    }

    fun cancelPatch(path: String) {
        val response = httpClient().send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(PatchInfo(path, minecraftVersion.get()))))
                .uri(URI.create(endpoint.get() + "/cancel-patch"))
                .auth()
                .contentTypeApplicationJson()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        logger.lifecycle("Cancelled patch $path")
    }
}
