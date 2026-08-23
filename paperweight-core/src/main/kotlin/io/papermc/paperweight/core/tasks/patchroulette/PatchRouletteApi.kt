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
import java.nio.charset.StandardCharsets
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder
import org.gradle.api.logging.Logging

class PatchRouletteApi(
    private val client: CloseableHttpClient,
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
        val request = ClassicRequestBuilder.get(apiUri("/patches/available", mapOf("minecraftVersion" to minecraftVersion))).build()
        val response = send(request)
        return gson.fromJson(response, typeToken<List<String>>())
    }

    fun getAllPatches(): List<Patch> {
        val request = ClassicRequestBuilder.get(apiUri("/patches", mapOf("minecraftVersion" to minecraftVersion))).build()
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
        val request = jsonPost(route, PatchInfo(path, minecraftVersion))
        send(request)
        logger.lifecycle("$action patch $path")
    }

    private fun send(request: ClassicHttpRequest): String {
        var response = execute(request, forceRefresh = false)
        if (response.code == 401) {
            // The first response is fully closed before refresh or interactive authorization begins.
            response = execute(request, forceRefresh = true)
        }
        if (response.code !in 200..299) {
            throw PaperweightException("Response status code: ${response.code}, body: ${response.body}")
        }
        return response.body
    }

    private fun execute(request: ClassicHttpRequest, forceRefresh: Boolean): ApiResponse {
        request.setHeader("Authorization", "Bearer ${accessToken(forceRefresh)}")
        return client.execute(request) { response ->
            ApiResponse(response.code, response.entity?.let(EntityUtils::toString).orEmpty())
        }
    }

    private fun jsonPost(route: String, payload: Any) = ClassicRequestBuilder.post(apiUri(route))
        .setEntity(gson.toJson(payload), ContentType.APPLICATION_JSON)
        .build()

    private fun apiUri(path: String, parameters: Map<String, String> = emptyMap()): URI {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return URI.create(endpoint.removeSuffix("/") + path + if (query.isEmpty()) "" else "?$query")
    }

    private data class PatchesInfo(val paths: List<String>, val minecraftVersion: String)

    private data class PatchInfo(val path: String, val minecraftVersion: String)

    private data class ApiResponse(val code: Int, val body: String)
}
