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

package io.papermc.paperweight.core.util

import com.google.gson.annotations.SerializedName
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.gson
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.gradle.api.logging.Logger

/**
 * An OAuth client for a protected resource. It uses dynamic client registration
 * and Authorization Code with PKCE, then retains its refresh token in Gradle's
 * cache for later invocations.
 */
internal class OAuthClient(
    private val httpClient: HttpClient,
    private val resourceUri: URI,
    private val cacheDirectory: Path,
    private val logger: Logger,
) {
    fun accessToken(): String {
        val configuration = discoverConfiguration()
        val credentials = loadCredentials()
        if (credentials != null) {
            val token = refreshAccessToken(configuration, credentials)
            if (token != null) {
                return token
            }
        }

        return authorize(configuration)
    }

    private fun discoverConfiguration(): OAuthConfiguration {
        val discoveryUri = resourceUri.resolve("/.well-known/oauth-authorization-server")
        val response = httpClient.send(
            HttpRequest.newBuilder(discoveryUri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not discover OAuth configuration: ${response.statusCode()} ${response.body()}")
        }

        val responseBody = gson.fromJson(response.body(), DiscoveryResponse::class.java)
        val authorizationEndpoint = responseBody.authorizationEndpoint.required("authorization_endpoint")
        val tokenEndpoint = responseBody.tokenEndpoint.required("token_endpoint")
        val registrationEndpoint = responseBody.registrationEndpoint.required("registration_endpoint")
        return OAuthConfiguration(authorizationEndpoint, tokenEndpoint, registrationEndpoint)
    }

    private fun authorize(configuration: OAuthConfiguration): String {
        val verifier = randomUrlSafeValue()
        val state = randomUrlSafeValue()
        val code = CompletableFuture<String>()
        val server = callbackServer(state, code)
        try {
            val redirectUri = URI.create("http://127.0.0.1:${server.address.port}$CALLBACK_PATH")
            val clientId = registerClient(configuration.registrationEndpoint, redirectUri)
            val authorizationUri = authorizationUri(configuration.authorizationEndpoint, clientId, redirectUri, verifier, state)
            openBrowser(authorizationUri)

            val authorizationCode = waitForAuthorizationCode(code)
            val token = exchangeCode(configuration.tokenEndpoint, authorizationCode, verifier, redirectUri, clientId)
            saveCredentials(OAuthCredentials(clientId, token.refreshToken.required("refresh_token")))
            return token.accessToken.required("access_token")
        } finally {
            server.stop(0)
        }
    }

    private fun refreshAccessToken(configuration: OAuthConfiguration, credentials: OAuthCredentials): String? {
        val body = formBody(
            "grant_type" to "refresh_token",
            "client_id" to credentials.clientId,
            "refresh_token" to credentials.refreshToken,
            "resource" to resourceUri.toString(),
        )
        val response = httpClient.send(
            formRequest(configuration.tokenEndpoint, body),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() in 400..499) {
            return null
        }
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not refresh OAuth access token: ${response.statusCode()} ${response.body()}")
        }

        val token = gson.fromJson(response.body(), TokenResponse::class.java)
        val refreshToken = token.refreshToken
        if (!refreshToken.isNullOrBlank()) {
            saveCredentials(OAuthCredentials(credentials.clientId, refreshToken))
        }
        return token.accessToken.required("access_token")
    }

    private fun authorizationUri(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: URI,
        verifier: String,
        state: String,
    ): URI {
        val parameters = formBody(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri.toString(),
            "resource" to resourceUri.toString(),
            "state" to state,
            "code_challenge" to sha256UrlSafe(verifier),
            "code_challenge_method" to "S256",
        )
        val separator = if (URI.create(authorizationEndpoint).rawQuery == null) "?" else "&"
        return URI.create(authorizationEndpoint + separator + parameters)
    }

    private fun openBrowser(authorizationUri: URI) {
        logger.lifecycle("Opening $authorizationUri in browser...")
        if (!Desktop.isDesktopSupported()) {
            logger.warn("Failed to open a browser. OAuth may not work in a headless environment because it requires a loopback endpoint.")
            return
        }
        try {
            Desktop.getDesktop().browse(authorizationUri)
        } catch (ex: Exception) {
            logger.warn(
                "Failed to open a browser: ${ex.message}. OAuth may not work in a headless environment because it requires a loopback endpoint."
            )
        }
    }

    private fun waitForAuthorizationCode(code: CompletableFuture<String>): String {
        try {
            return code.get(5, TimeUnit.MINUTES)
        } catch (ex: Exception) {
            throw PaperweightException("Timed out waiting for OAuth authorization.", ex)
        }
    }

    private fun callbackServer(state: String, code: CompletableFuture<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(CALLBACK_PATH) { exchange -> handleCallback(exchange, state, code) }
        server.start()
        return server
    }

    private fun handleCallback(exchange: HttpExchange, state: String, code: CompletableFuture<String>) {
        val query = parseQuery(exchange.requestURI.rawQuery)
        val error = query["error"]
        val message: String
        val authorizationCode: String?
        when {
            exchange.requestMethod != "GET" -> {
                message = "OAuth callback must use GET."
                authorizationCode = null
            }
            query["state"] != state -> {
                message = "OAuth callback state did not match."
                authorizationCode = null
            }
            error != null -> {
                message = "OAuth authorization failed: $error"
                authorizationCode = null
            }
            query["code"].isNullOrBlank() -> {
                message = "OAuth callback did not include an authorization code."
                authorizationCode = null
            }
            else -> {
                message = "Authorization complete. You may close this tab."
                authorizationCode = query.getValue("code")
            }
        }
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(if (authorizationCode == null) 400 else 200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        if (authorizationCode == null) {
            code.completeExceptionally(PaperweightException(message))
        } else {
            code.complete(authorizationCode)
        }
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) {
            return emptyMap()
        }
        val values = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            if (part.isEmpty()) {
                continue
            }
            val separator = part.indexOf('=')
            val encodedKey = if (separator == -1) part else part.substring(0, separator)
            val encodedValue = if (separator == -1) "" else part.substring(separator + 1)
            val key = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8)
            val value = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8)
            values[key] = value
        }
        return values
    }

    private fun registerClient(registrationEndpoint: String, redirectUri: URI): String {
        val body = gson.toJson(
            mapOf(
                "application_type" to "native",
                "redirect_uris" to listOf(redirectUri.toString()),
                "grant_types" to listOf("authorization_code", "refresh_token"),
                "response_types" to listOf("code"),
                "token_endpoint_auth_method" to "none",
            )
        )
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create(registrationEndpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not register an OAuth client: ${response.statusCode()} ${response.body()}")
        }
        val registration = gson.fromJson(response.body(), RegistrationResponse::class.java)
        return registration.clientId.required("client_id")
    }

    private fun exchangeCode(
        tokenEndpoint: String,
        code: String,
        verifier: String,
        redirectUri: URI,
        clientId: String,
    ): TokenResponse {
        val body = formBody(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to verifier,
            "resource" to resourceUri.toString(),
        )
        val response = httpClient.send(
            formRequest(tokenEndpoint, body),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not exchange OAuth authorization code: ${response.statusCode()} ${response.body()}")
        }
        return gson.fromJson(response.body(), TokenResponse::class.java)
    }

    private fun formRequest(tokenEndpoint: String, body: String): HttpRequest {
        return HttpRequest.newBuilder(URI.create(tokenEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun loadCredentials(): OAuthCredentials? {
        val cacheFile = cacheFile()
        if (!Files.isRegularFile(cacheFile)) {
            return null
        }
        return try {
            gson.fromJson(Files.readString(cacheFile), OAuthCredentials::class.java)
        } catch (ex: Exception) {
            logger.warn("Could not read cached OAuth credentials: ${ex.message}")
            null
        }
    }

    private fun saveCredentials(credentials: OAuthCredentials) {
        Files.createDirectories(cacheDirectory)
        val cacheFile = cacheFile()
        val tmp = Files.createTempFile(cacheDirectory, "oauth-", ".tmp")
        try {
            Files.writeString(tmp, gson.toJson(credentials), StandardOpenOption.TRUNCATE_EXISTING)
            runCatching {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            try {
                Files.move(tmp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun cacheFile(): Path {
        val normalized = resourceUri.toString().removeSuffix("/")
        val resourceHash = sha256UrlSafe(normalized)
        return cacheDirectory.resolve("oauth-$resourceHash.json")
    }

    private fun formBody(vararg parameters: Pair<String, String>): String {
        return parameters.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }

    private fun randomUrlSafeValue(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256UrlSafe(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun String?.required(field: String): String {
        if (this.isNullOrBlank()) {
            throw PaperweightException("OAuth response did not include $field.")
        }
        return this
    }

    private class DiscoveryResponse(
        @SerializedName("authorization_endpoint") val authorizationEndpoint: String?,
        @SerializedName("token_endpoint") val tokenEndpoint: String?,
        @SerializedName("registration_endpoint") val registrationEndpoint: String?,
    )

    private class OAuthConfiguration(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String,
    )

    private class RegistrationResponse(@SerializedName("client_id") val clientId: String?)

    private class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
    )

    private class OAuthCredentials(val clientId: String, val refreshToken: String)

    private companion object {
        const val CALLBACK_PATH = "/oauth/callback"
    }
}
