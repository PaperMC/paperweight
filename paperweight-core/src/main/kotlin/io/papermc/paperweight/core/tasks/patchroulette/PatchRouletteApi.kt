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
    private val host: String,
    private val accessToken: String,
    private val minecraftVersion: String,
) {
    companion object {
        private val logger = Logging.getLogger(PatchRouletteApi::class.java)
    }
    enum class Status { WIP, DONE, AVAILABLE }

    data class Patch(val path: String, val status: Status, val responsibleUser: String?)

    fun getAvailablePatches(): List<String> {
        val request = HttpRequest.newBuilder()
            .GET()
            .uri(apiUri("/patches/available", mapOf("minecraftVersion" to minecraftVersion)))
        val response = send(request)
        return gson.fromJson(response, typeToken<List<String>>())
    }

    fun getAllPatches(): List<Patch> {
        val request = HttpRequest.newBuilder()
            .GET()
            .uri(apiUri("/patches", mapOf("minecraftVersion" to minecraftVersion)))
        val response = send(request)
        return gson.fromJson(response, typeToken<List<Patch>>())
    }

    fun setPatches(paths: List<String>) {
        val request = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(PatchesInfo(paths, minecraftVersion))))
            .uri(apiUri("/patches/init"))
            .contentTypeApplicationJson()
        send(request)
        logger.lifecycle("Set patches for $minecraftVersion")
    }

    fun clearPatches() {
        val request = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(mapOf("minecraftVersion" to minecraftVersion))))
            .uri(apiUri("/patches/clear"))
            .contentTypeApplicationJson()
        send(request)
        logger.lifecycle("Cleared patches for $minecraftVersion")
    }

    fun startPatches(paths: List<String>): List<String> {
        val request = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(PatchesInfo(paths, minecraftVersion))))
            .uri(apiUri("/patches/start"))
            .contentTypeApplicationJson()
        val response = send(request)
        val startedPatches = gson.fromJson<List<String>>(response, typeToken<List<String>>())
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
        val request = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(PatchInfo(path, minecraftVersion))))
            .uri(apiUri(route))
            .contentTypeApplicationJson()
        send(request)
        logger.lifecycle("$action patch $path")
    }

    private fun send(request: HttpRequest.Builder): String {
        val response = client.send(
            request.header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(30))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Response status code: ${response.statusCode()}, body: ${response.body()}")
        }
        return response.body()
    }

    private fun HttpRequest.Builder.contentTypeApplicationJson() = header("Content-Type", "application/json")

    private fun apiUri(path: String, parameters: Map<String, String> = emptyMap()): URI {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return URI.create(host.removeSuffix("/") + "/api" + path + if (query.isEmpty()) "" else "?$query")
    }

    private data class PatchesInfo(val paths: List<String>, val minecraftVersion: String)

    private data class PatchInfo(val path: String, val minecraftVersion: String)
}
