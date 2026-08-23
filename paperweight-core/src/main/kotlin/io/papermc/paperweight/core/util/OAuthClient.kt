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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.gradle.api.logging.Logging

/**
 * An OAuth client for a protected resource. It uses dynamic client registration
 * and Authorization Code with PKCE, then retains its refresh token in Gradle's
 * cache for later invocations. Cached credentials are keyed by the authorization
 * server that issued them, so resources fronted by several servers coexist.
 *
 * Required scopes are not configured up front; they are learned at runtime from
 * WWW-Authenticate challenges ([RFC 6750]) and accumulate over the lifetime of
 * this client.
 */
internal class OAuthClient(
    private val resourceUri: URI,
    private val cacheDirectory: Path,
    private val fetch: (OAuthHttpRequest) -> OAuthHttpResponse = ::defaultFetch,
) {
    private val learnedScopes = mutableSetOf<String>()
    private val resourceKey = sha256UrlSafe(resourceUri.toString().removeSuffix("/"))
    private var issuerOrigin: String? = null

    fun accessToken(
        discoveryUri: URI? = null,
        requireScopes: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
    ): String {
        learnedScopes.addAll(requireScopes)

        // Fast path: a cached token for this resource, before the authorization server is known.
        if (!forceRefresh) {
            cachedToken()?.let { return it }
        }

        val configuration = discoverConfiguration(discoveryUri)
        val issuer = issuerOrigin ?: error("Authorization server origin was not resolved")

        val credentials = loadCredentials(issuer)
        if (credentials != null) {
            // Refresh silently first: refreshing re-evaluates our entitlements server-side,
            // so widened grants are picked up without user interaction.
            val refreshed = refreshAccessToken(configuration, credentials)
            if (refreshed == null) {
                logger.lifecycle("Cached OAuth credentials for $resourceUri are invalid or expired.")
            } else {
                val granted = requireNotNull(loadCredentials(issuer)).grantedScopes
                if (granted.containsAll(learnedScopes)) {
                    return refreshed
                }
                logger.lifecycle("Refreshed OAuth credentials for $resourceUri still lack required scope(s).")
            }
        }

        val token = authorize(configuration, issuer)
        val granted = loadCredentials(issuer)?.grantedScopes.orEmpty()
        if (!granted.containsAll(learnedScopes)) {
            throw PaperweightException(
                "OAuth authorization for $resourceUri did not grant the required scope(s): " +
                    learnedScopes.subtract(granted).sorted().joinToString(" ") +
                    ". Your account may not be entitled to this operation."
            )
        }
        return token
    }

    /** Returns null when no credentials exist yet, leaving requests to be driven by auth challenges. */
    fun tryAccessToken(discoveryUri: URI? = null): String? {
        loadCredentials() ?: return null
        return accessToken(discoveryUri)
    }

    private fun cachedToken(): String? = cachedToken(loadCredentials())

    private fun cachedToken(credentials: OAuthCredentials?): String? {
        return credentials?.takeIf { it.hasValidAccessToken() && it.grantedScopes.containsAll(learnedScopes) }?.accessToken
    }

    private fun discoverConfiguration(discoveryUri: URI?): OAuthConfiguration {
        val endpoint = discoveryUri ?: resourceUri.resolve("/.well-known/oauth-authorization-server")
        issuerOrigin = originOf(endpoint)
        val response = fetch(OAuthHttpRequest(endpoint))
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not discover OAuth configuration: ${response.statusCode()} ${response.body()}")
        }

        val responseBody = gson.fromJson(response.body(), DiscoveryResponse::class.java)
        val authorizationEndpoint = responseBody.authorizationEndpoint.required("authorization_endpoint")
        val tokenEndpoint = responseBody.tokenEndpoint.required("token_endpoint")
        val registrationEndpoint = responseBody.registrationEndpoint.required("registration_endpoint")
        return OAuthConfiguration(authorizationEndpoint, tokenEndpoint, registrationEndpoint)
    }

    private fun authorizationServerMetadataUri(authorizationServer: URI): URI {
        val normalized = authorizationServer.toString().removeSuffix("/")
        return URI.create("$normalized/.well-known/oauth-authorization-server")
    }

    fun discoveryUri(resourceMetadataUri: URI?): URI? {
        if (resourceMetadataUri == null) {
            return null
        }
        val response = fetch(OAuthHttpRequest(resourceMetadataUri))
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not discover OAuth protected resource metadata: ${response.statusCode()} ${response.body()}")
        }
        val resourceMetadata = gson.fromJson(response.body(), ResourceMetadataResponse::class.java)
        val authorizationServer = resourceMetadata.authorizationServers?.firstOrNull().required("authorization_servers")
        return authorizationServerMetadataUri(URI.create(authorizationServer))
    }

    private fun authorize(configuration: OAuthConfiguration, issuer: String): String {
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
            saveCredentials(issuer, newCredentials(issuer, clientId, token.refreshToken.required("refresh_token"), token, learnedScopes))
            logger.lifecycle("OAuth authorization succeeded for $resourceUri.")
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
        val response = fetch(formRequest(configuration.tokenEndpoint, body))
        if (response.statusCode() in 400..499) {
            val error = runCatching { gson.fromJson(response.body(), ErrorResponse::class.java).error }.getOrNull()
            if (error == "invalid_grant") {
                return null
            }
            throw PaperweightException("Could not refresh OAuth access token: ${response.statusCode()} ${response.body()}")
        }
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not refresh OAuth access token: ${response.statusCode()} ${response.body()}")
        }

        val token = gson.fromJson(response.body(), TokenResponse::class.java)
        saveCredentials(
            credentials.issuer,
            newCredentials(
                credentials.issuer,
                credentials.clientId,
                token.refreshToken ?: credentials.refreshToken,
                token,
                // A missing scope attribute on a refresh means the previous grants are unchanged.
                credentials.grantedScopes,
            )
        )
        return token.accessToken.required("access_token")
    }

    private fun authorizationUri(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: URI,
        verifier: String,
        state: String,
    ): URI {
        val parameters = mutableListOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri.toString(),
            "resource" to resourceUri.toString(),
            "state" to state,
            "code_challenge" to sha256UrlSafe(verifier),
            "code_challenge_method" to "S256",
        )
        if (learnedScopes.isNotEmpty()) {
            parameters += "scope" to learnedScopes.sorted().joinToString(" ")
        }
        val separator = if (URI.create(authorizationEndpoint).rawQuery == null) "?" else "&"
        return URI.create(authorizationEndpoint + separator + formBody(*parameters.toTypedArray()))
    }

    private fun openBrowser(authorizationUri: URI) {
        logger.lifecycle("Opening $authorizationUri in browser...")
        if (openWithDesktop(authorizationUri) || openWithXdgOpen(authorizationUri)) {
            logger.lifecycle("Go to your browser to complete authorization; waiting for the callback...")
            return
        }
        logger.warn("Could not open a browser. To authorize, open the above URL.")
    }

    private fun openWithDesktop(authorizationUri: URI): Boolean {
        if (!Desktop.isDesktopSupported()) {
            return false
        }
        return try {
            Desktop.getDesktop().browse(authorizationUri)
            true
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.debug("Could not open a browser through Desktop", ex)
            false
        } catch (ex: Exception) {
            logger.debug("Could not open a browser through Desktop", ex)
            false
        }
    }

    private fun openWithXdgOpen(authorizationUri: URI): Boolean {
        // Fallback for Linux systems where Desktop.browse fails due to missing GTK/GIO desktop integration.
        return try {
            val process = ProcessBuilder("xdg-open", authorizationUri.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // xdg-open should exit promptly with 0 on success, non-zero on failure.
            // A timeout indicates a hang/broken handler - treat as failure so fallback URL is shown.
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                process.exitValue() == 0
            } else {
                process.destroyForcibly()
                false
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.debug("Could not open a browser using xdg-open", ex)
            false
        } catch (ex: Exception) {
            logger.debug("Could not open a browser using xdg-open", ex)
            false
        }
    }

    private fun waitForAuthorizationCode(code: CompletableFuture<String>): String {
        try {
            return code.get(5, TimeUnit.MINUTES)
        } catch (ex: TimeoutException) {
            throw PaperweightException("Timed out waiting for OAuth authorization.", ex)
        } catch (ex: ExecutionException) {
            throw PaperweightException("OAuth authorization failed.", ex.cause ?: ex)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PaperweightException("Interrupted while waiting for OAuth authorization.", ex)
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
        val registrationRequest = mutableMapOf<String, Any>(
            "application_type" to "native",
            "redirect_uris" to listOf(redirectUri.toString()),
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to "none",
        )
        if (learnedScopes.isNotEmpty()) {
            registrationRequest["scope"] = learnedScopes.sorted().joinToString(" ")
        }
        val body = gson.toJson(registrationRequest)
        val response = fetch(OAuthHttpRequest(URI.create(registrationEndpoint), "POST", mapOf("Content-Type" to "application/json"), body))
        if (response.statusCode() !in 200..299) {
            throw PaperweightException(
                "Could not register an OAuth client: ${response.statusCode()} ${response.body()}. " +
                    "For Cloudflare Access, enable allow loopback clients for this application."
            )
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
        val response = fetch(formRequest(tokenEndpoint, body))
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not exchange OAuth authorization code: ${response.statusCode()} ${response.body()}")
        }
        return gson.fromJson(response.body(), TokenResponse::class.java)
    }

    private fun formRequest(tokenEndpoint: String, body: String): OAuthHttpRequest {
        return OAuthHttpRequest(
            URI.create(tokenEndpoint),
            "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body,
        )
    }

    /** Loads any cached credentials for this resource, preferring a valid token when several servers have issued one. */
    private fun loadCredentials(): OAuthCredentials? {
        if (!Files.isDirectory(cacheDirectory)) {
            return null
        }
        Files.newDirectoryStream(cacheDirectory, "oauth-$resourceKey-*.json").use { entries ->
            val credentials = entries.mapNotNull { readCredentials(it) }
            return credentials.firstOrNull { it.hasValidAccessToken() }
                ?: credentials.maxByOrNull { it.expiresAt }
        }
    }

    private fun loadCredentials(issuer: String): OAuthCredentials? {
        val cacheFile = cacheFile(issuer)
        return if (Files.isRegularFile(cacheFile)) readCredentials(cacheFile) else null
    }

    private fun readCredentials(cacheFile: Path): OAuthCredentials? {
        return try {
            gson.fromJson(Files.readString(cacheFile), OAuthCredentials::class.java)
        } catch (ex: Exception) {
            logger.warn("Could not read cached OAuth credentials: ${ex.message}")
            null
        }
    }

    private fun saveCredentials(issuer: String, credentials: OAuthCredentials) {
        Files.createDirectories(cacheDirectory)
        val cacheFile = cacheFile(issuer)
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

    private fun cacheFile(issuer: String): Path {
        return cacheDirectory.resolve("oauth-$resourceKey-${sha256UrlSafe(issuer)}.json")
    }

    private fun originOf(uri: URI): String {
        return "${uri.scheme}://${uri.authority}"
    }

    private fun newCredentials(
        issuer: String,
        clientId: String,
        refreshToken: String,
        token: TokenResponse,
        requested: Set<String>,
    ): OAuthCredentials {
        val expiresAt = token.expiresIn?.let { expiresIn ->
            (System.currentTimeMillis() / 1000) + (expiresIn - EXPIRY_SKEW_SECONDS).coerceAtLeast(0)
        } ?: 0
        val grantedScopes = token.scope?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet() ?: requested
        return OAuthCredentials(issuer, clientId, refreshToken, token.accessToken.required("access_token"), expiresAt, grantedScopes)
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

    private class ResourceMetadataResponse(@SerializedName("authorization_servers") val authorizationServers: List<String>?)

    private class OAuthConfiguration(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String,
    )

    private class RegistrationResponse(@SerializedName("client_id") val clientId: String?)

    private class ErrorResponse(@SerializedName("error") val error: String?)

    private class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Long?,
        @SerializedName("scope") val scope: String?,
    )

    private data class OAuthCredentials(
        val issuer: String,
        val clientId: String,
        val refreshToken: String,
        val accessToken: String,
        val expiresAt: Long,
        val grantedScopes: Set<String>,
    ) {
        fun hasValidAccessToken(): Boolean = expiresAt > System.currentTimeMillis() / 1000
    }

    private companion object {
        private val logger = Logging.getLogger(OAuthClient::class.java)

        const val CALLBACK_PATH = "/oauth/callback"
        const val EXPIRY_SKEW_SECONDS = 30L

        private val defaultHttpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        fun defaultFetch(request: OAuthHttpRequest): OAuthHttpResponse {
            val builder = HttpRequest.newBuilder(request.uri).timeout(Duration.ofSeconds(30))
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            if (request.body == null) {
                builder.method(request.method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.method(request.method, HttpRequest.BodyPublishers.ofString(request.body))
            }
            return try {
                val response = defaultHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                OAuthHttpResponse(response.statusCode(), response.body())
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PaperweightException("Interrupted during OAuth HTTP request.", ex)
            } catch (ex: Exception) {
                throw PaperweightException("OAuth HTTP request failed.", ex)
            }
        }
    }
}

internal data class OAuthHttpRequest(
    val uri: URI,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

internal data class OAuthHttpResponse(private val status: Int, private val content: String) {
    fun statusCode(): Int = status

    fun body(): String = content
}
