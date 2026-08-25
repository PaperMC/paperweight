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
import io.papermc.paperweight.util.gson
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.gradle.api.logging.Logging

class PatchRouletteApi(
    private val client: HttpClient,
    private val endpoint: String,
    private val minecraftVersion: String,
    private val accessToken: (forceRefresh: Boolean) -> String,
) {
    companion object {
        private val logger = Logging.getLogger(PatchRouletteApi::class.java)
    }
    enum class Status { WIP, DONE, AVAILABLE }

    data class Patch(val path: String, val status: Status, val responsibleUser: String?)

    fun getAvailablePatches(): List<String> {
        val request = HttpRequest.newBuilder(apiUri("/patches/available", mapOf("minecraftVersion" to minecraftVersion))).GET()
        val response = send(request)
        return gson.fromJson(response, typeToken<List<String>>())
    }

    fun getAllPatches(): List<Patch> {
        val request = HttpRequest.newBuilder(apiUri("/patches", mapOf("minecraftVersion" to minecraftVersion))).GET()
        val response = send(request)
        return gson.fromJson(response, typeToken<List<Patch>>())
    }

    fun setPatches(paths: List<String>) {
        val request = jsonPost("/patches/init", PatchesInfo(paths, minecraftVersion))
        send(request)
        logger.lifecycle("Set patches for $minecraftVersion")
    }

    fun clearPatches() {
        val request = jsonPost("/patches/clear", mapOf("minecraftVersion" to minecraftVersion))
        send(request)
        logger.lifecycle("Cleared patches for $minecraftVersion")
    }

    fun startPatches(paths: List<String>): List<String> {
        val request = jsonPost("/patches/start", PatchesInfo(paths, minecraftVersion))
        val response = send(request)
        val startedPatches = gson.fromJson<List<Patch>>(response, typeToken<List<Patch>>()).map(Patch::path)
        logger.lifecycle("Started patches $startedPatches")
        return startedPatches
    }

    fun completePatch(path: String) {
        patchAction("/patches/complete", path, "Completed")
    }

    fun cancelPatch(path: String) {
        patchAction("/patches/cancel", path, "Cancelled")
    }

    private fun patchAction(route: String, path: String, action: String) {
        val request = jsonPost(route, PatchInfo(path, minecraftVersion))
        send(request)
        logger.lifecycle("$action patch $path")
    }

    private fun send(request: HttpRequest.Builder): String {
        var response = execute(request, forceRefresh = false)
        if (response.statusCode() == 401) {
            response = execute(request, forceRefresh = true)
        }
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        return response.body()
    }

    private fun execute(request: HttpRequest.Builder, forceRefresh: Boolean): HttpResponse<String> {
        val authenticated = request
            .timeout(Duration.ofSeconds(30))
            .setHeader("Authorization", "Bearer ${accessToken(forceRefresh)}")
            .build()
        return try {
            client.send(authenticated, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PaperweightException("Interrupted during Patch Roulette API request.", ex)
        } catch (ex: IOException) {
            throw PaperweightException("Patch Roulette API request failed.", ex)
        }
    }

    private fun jsonPost(route: String, payload: Any): HttpRequest.Builder = HttpRequest.newBuilder(apiUri(route))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))

    private fun apiUri(path: String, parameters: Map<String, String> = emptyMap()): URI {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return URI.create(endpoint.removeSuffix("/") + path + if (query.isEmpty()) "" else "?$query")
    }

    private data class PatchesInfo(val paths: List<String>, val minecraftVersion: String)

    private data class PatchInfo(val path: String, val minecraftVersion: String)
}
