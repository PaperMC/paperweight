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
import io.papermc.paperweight.util.withLock
import java.awt.Desktop
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
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
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder
import org.gradle.api.logging.Logging

/**
 * OAuth client for one Cloudflare Access-protected resource using [Cloudflare
 * Managed OAuth](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/managed-oauth/).
 * See [the announcement](https://blog.cloudflare.com/managed-oauth-for-access/)
 * for background.
 *
 * This implements protected-resource discovery ([RFC 9728]), authorization-server
 * discovery ([RFC 8414]), resource indicators ([RFC 8707]), dynamic client
 * registration ([RFC 7591]), and Authorization Code with PKCE S256 ([RFC 7636]).
 * Access tokens are sent as bearer authorization headers ([RFC 6750]). Refresh
 * tokens are optional; when supplied, they are retained in the configured cache
 * directory and rotated when the authorization server returns a replacement.
 *
 * This deliberately supports one resource and the first advertised authorization
 * server per instance. It requires an open registration endpoint, a public client
 * using token endpoint authentication method `none`, and a loopback redirect URI.
 * It does not implement scope negotiation or escalation, multiple authorization
 * servers, confidential clients, device authorization, DPoP, token introspection,
 * signed metadata, or bearer token transport outside the Authorization header.
 * Servers advertising an unsupported profile are rejected rather than partially
 * interpreted.
 */
class CloudflareAccessManagedOAuthClient(
    private val httpClient: HttpClient,
    private val resourceUri: URI,
    private val cacheDirectory: Path,
) {
    data class AccessToken(val value: String)

    fun accessToken(): AccessToken = accessToken(null)

    fun tokenForRetry(rejected: AccessToken): AccessToken = accessToken(rejected)

    private companion object {
        private val logger = Logging.getLogger(CloudflareAccessManagedOAuthClient::class.java)

        const val CALLBACK_PATH = "/oauth/callback"
        const val EXPIRY_SKEW_SECONDS = 30L
        const val CLIENT_NAME = "paperweight"
        const val CLIENT_URI = "https://github.com/PaperMC/paperweight"
    }

    private val resourceKey = sha256UrlSafe(resourceUri.toString())
    private val configuration by lazy(::discoverConfiguration)
    private val credentialCacheFile by lazy {
        cacheDirectory.resolve("oauth-$resourceKey-${sha256UrlSafe(configuration.issuer.toString())}.json")
    }
    private val credentialLockFile by lazy {
        credentialCacheFile.resolveSibling("${credentialCacheFile.fileName.toString().removeSuffix(".json")}.lock")
    }

    private fun accessToken(rejected: AccessToken?): AccessToken {
        val configuration = configuration
        var authorizationBaseline: OAuthCredentials? = null
        val cachedAccessToken = withLock(credentialLockFile) {
            val currentCredentials = loadCredentials()
            if (
                currentCredentials?.hasValidAccessToken() == true &&
                currentCredentials.accessToken != rejected?.value
            ) {
                return@withLock AccessToken(currentCredentials.accessToken)
            }
            if (currentCredentials != null) {
                refreshAccessToken(configuration, currentCredentials)
                    ?.takeIf { it != rejected }
                    ?.let { return@withLock it }
                logger.lifecycle("Cached OAuth credentials for $resourceUri are invalid or expired.")
            }
            authorizationBaseline = currentCredentials
            null
        }
        if (cachedAccessToken != null) {
            return cachedAccessToken
        }

        val authorizedCredentials = authorize(configuration)
        return withLock(credentialLockFile) {
            // Prefer credentials another process saved while this browser flow was running.
            val currentCredentials = loadCredentials()
            if (
                currentCredentials != authorizationBaseline &&
                currentCredentials?.hasValidAccessToken() == true &&
                currentCredentials.accessToken != rejected?.value
            ) {
                AccessToken(currentCredentials.accessToken)
            } else {
                if (authorizedCredentials.accessToken == rejected?.value) {
                    throw PaperweightException("OAuth authorization returned the rejected access token.")
                }
                saveCredentials(authorizedCredentials)
                AccessToken(authorizedCredentials.accessToken)
            }
        }
    }

    private fun discoverConfiguration(): OAuthConfiguration {
        validateHttps(resourceUri, "OAuth resource identifier")

        val resourceMetadataUri = resourceMetadataUri()
        val resourceMetadata = gson.fromJson(
            fetchJson(resourceMetadataUri, "OAuth protected resource metadata"),
            ResourceMetadataResponse::class.java,
        )
        if (resourceMetadata.resource != resourceUri.toString()) {
            throw PaperweightException(
                "OAuth protected resource metadata identified ${resourceMetadata.resource} instead of $resourceUri."
            )
        }
        val issuer = URI.create(resourceMetadata.authorizationServers?.firstOrNull().required("authorization_servers"))
        validateHttps(issuer, "OAuth issuer identifier", allowQuery = false)

        val metadataUri = authorizationServerMetadataUri(issuer)
        val metadata = gson.fromJson(
            fetchJson(metadataUri, "OAuth authorization server metadata"),
            DiscoveryResponse::class.java,
        )
        if (metadata.issuer != issuer.toString()) {
            throw PaperweightException("OAuth authorization server metadata identified ${metadata.issuer} instead of $issuer.")
        }
        if (metadata.responseTypesSupported?.contains("code") != true) {
            throw PaperweightException("OAuth authorization server does not advertise the code response type.")
        }
        val grantTypes = metadata.grantTypesSupported ?: listOf("authorization_code", "implicit")
        if ("authorization_code" !in grantTypes) {
            throw PaperweightException("OAuth authorization server does not support the authorization_code grant.")
        }
        val authMethods = metadata.tokenEndpointAuthMethodsSupported ?: listOf("client_secret_basic")
        if ("none" !in authMethods) {
            throw PaperweightException("OAuth authorization server does not support public clients.")
        }
        if (metadata.codeChallengeMethodsSupported?.contains("S256") != true) {
            throw PaperweightException("OAuth authorization server does not support PKCE S256.")
        }

        return OAuthConfiguration(
            issuer,
            secureEndpoint(metadata.authorizationEndpoint.required("authorization_endpoint"), "authorization_endpoint"),
            secureEndpoint(metadata.tokenEndpoint.required("token_endpoint"), "token_endpoint"),
            secureEndpoint(metadata.registrationEndpoint.required("registration_endpoint"), "registration_endpoint"),
            "refresh_token" in grantTypes,
        )
    }

    private fun fetchJson(uri: URI, description: String): String {
        val response = send(request(uri).GET().build())
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not fetch $description: ${response.statusCode()} ${response.body()}")
        }
        val contentType = response.headers().firstValue("Content-Type").orElse(null)
        if (contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) != true) {
            throw PaperweightException("$description did not use the application/json content type.")
        }
        return response.body()
    }

    private fun resourceMetadataUri(): URI {
        val path = resourceUri.rawPath.orEmpty().takeUnless { it == "/" }.orEmpty()
        val query = resourceUri.rawQuery?.let { "?$it" }.orEmpty()
        return URI.create("${origin(resourceUri)}/.well-known/oauth-protected-resource$path$query")
    }

    private fun authorizationServerMetadataUri(issuer: URI): URI = URI.create(
        "${origin(issuer)}/.well-known/oauth-authorization-server${issuer.rawPath.orEmpty().removeSuffix("/")}"
    )

    private fun validateHttps(uri: URI, description: String, allowQuery: Boolean = true) {
        if (
            uri.scheme != "https" ||
            uri.rawAuthority.isNullOrBlank() ||
            uri.rawFragment != null ||
            (!allowQuery && uri.rawQuery != null)
        ) {
            throw PaperweightException("$description is not a supported HTTPS URI: $uri")
        }
    }

    private fun secureEndpoint(value: String, field: String): URI {
        val uri = URI.create(value)
        validateHttps(uri, "OAuth $field")
        return uri
    }

    private fun origin(uri: URI): String = "${uri.scheme}://${uri.rawAuthority}"

    private fun authorize(configuration: OAuthConfiguration): OAuthCredentials {
        val verifier = randomPkceVerifier()
        val state = randomUrlSafeValue()
        val code = CompletableFuture<String>()
        val server = callbackServer(state, code)
        try {
            val redirectUri = URI.create("http://127.0.0.1:${server.address.port}$CALLBACK_PATH")
            val clientId = registerClient(configuration.registrationEndpoint, redirectUri, configuration.supportsRefresh)
            val authorizationUri = authorizationUri(configuration.authorizationEndpoint, clientId, redirectUri, verifier, state)
            openBrowser(authorizationUri)

            val authorizationCode = waitForAuthorizationCode(code)
            val token = exchangeCode(configuration.tokenEndpoint, authorizationCode, verifier, redirectUri, clientId)
            val credentials = newCredentials(clientId, token.refreshToken, token)
            logger.lifecycle("OAuth authorization succeeded for $resourceUri.")
            return credentials
        } finally {
            server.stop(0)
        }
    }

    private fun refreshAccessToken(configuration: OAuthConfiguration, credentials: OAuthCredentials): AccessToken? {
        if (!configuration.supportsRefresh || credentials.refreshToken == null) {
            return null
        }
        val request = formRequest(
            configuration.tokenEndpoint,
            "grant_type" to "refresh_token",
            "client_id" to credentials.clientId,
            "refresh_token" to credentials.refreshToken,
            "resource" to resourceUri.toString(),
        )
        val response = send(request)
        val responseBody = response.body()
        if (response.statusCode() in 400..499) {
            val error = runCatching { gson.fromJson(responseBody, ErrorResponse::class.java).error }.getOrNull()
            if (error == "invalid_grant" || error == "invalid_client") {
                return null
            }
        }
        if (response.statusCode() !in 200..299) {
            throw PaperweightException("Could not refresh OAuth access token: ${response.statusCode()} $responseBody")
        }
        val token = gson.fromJson(responseBody, TokenResponse::class.java)
        val refreshed = newCredentials(credentials.clientId, token.refreshToken ?: credentials.refreshToken, token)
        saveCredentials(refreshed)
        return AccessToken(refreshed.accessToken)
    }

    private fun authorizationUri(
        authorizationEndpoint: URI,
        clientId: String,
        redirectUri: URI,
        verifier: String,
        state: String,
    ): URI {
        val parameters = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri.toString(),
            "resource" to resourceUri.toString(),
            "state" to state,
            "code_challenge" to sha256UrlSafe(verifier),
            "code_challenge_method" to "S256",
        )
        return URIBuilder(authorizationEndpoint)
            .addParameters(parameters.map { BasicNameValuePair(it.first, it.second) })
            .build()
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
        if (exchange.requestMethod != "GET") {
            respondToCallback(exchange, 400, "OAuth callback must use GET.")
            return
        }
        val query = URIBuilder(exchange.requestURI).queryParams.associate { it.name to it.value }
        if (query["state"] != state) {
            respondToCallback(exchange, 400, "OAuth callback state did not match.")
            return
        }

        val error = query["error"]
        val authorizationCode = query["code"]
        val message = when {
            error != null -> "OAuth authorization failed: $error"
            authorizationCode.isNullOrBlank() -> "OAuth callback did not include an authorization code."
            else -> "Authorization complete. You may close this tab."
        }
        respondToCallback(exchange, if (authorizationCode.isNullOrBlank() || error != null) 400 else 200, message)
        if (authorizationCode.isNullOrBlank() || error != null) {
            code.completeExceptionally(PaperweightException(message))
        } else {
            code.complete(authorizationCode)
        }
    }

    private fun respondToCallback(exchange: HttpExchange, statusCode: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun registerClient(registrationEndpoint: URI, redirectUri: URI, supportsRefresh: Boolean): String {
        val grantTypes = listOfNotNull("authorization_code", "refresh_token".takeIf { supportsRefresh })
        val registrationRequest = mapOf(
            "client_name" to CLIENT_NAME,
            "client_uri" to CLIENT_URI,
            "redirect_uris" to listOf(redirectUri.toString()),
            "grant_types" to grantTypes,
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to "none",
        )
        val body = gson.toJson(registrationRequest)
        val response = send(
            request(registrationEndpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
        )
        if (response.statusCode() !in 200..299) {
            throw PaperweightException(
                "Could not register an OAuth client: ${response.statusCode()} ${response.body()}. " +
                    "For Cloudflare Access, enable allow loopback clients for this application."
            )
        }
        return gson.fromJson(response.body(), RegistrationResponse::class.java).clientId.required("client_id")
    }

    private fun exchangeCode(
        tokenEndpoint: URI,
        code: String,
        verifier: String,
        redirectUri: URI,
        clientId: String,
    ): TokenResponse {
        val request = formRequest(
            tokenEndpoint,
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to verifier,
            "resource" to resourceUri.toString(),
        )
        val response = send(request)
        if (response.statusCode() !in 200..299) {
            throw PaperweightException(
                "Could not exchange OAuth authorization code: ${response.statusCode()} ${response.body()}"
            )
        }
        return gson.fromJson(response.body(), TokenResponse::class.java)
    }

    private fun formRequest(tokenEndpoint: URI, vararg parameters: Pair<String, String>): HttpRequest =
        request(tokenEndpoint)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody(*parameters), StandardCharsets.UTF_8))
            .build()

    private fun request(uri: URI): HttpRequest.Builder =
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))

    private fun formBody(vararg parameters: Pair<String, String>): String = parameters.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        throw PaperweightException("Interrupted during OAuth HTTP request.", ex)
    } catch (ex: IOException) {
        throw PaperweightException("OAuth HTTP request failed.", ex)
    }

    private fun loadCredentials(): OAuthCredentials? {
        if (!Files.isRegularFile(credentialCacheFile)) {
            return null
        }
        return try {
            gson.fromJson(Files.readString(credentialCacheFile), OAuthCredentials::class.java)
        } catch (ex: Exception) {
            logger.warn("Could not read cached OAuth credentials: ${ex.message}")
            null
        }
    }

    private fun saveCredentials(credentials: OAuthCredentials) {
        Files.createDirectories(cacheDirectory)
        val tmp = Files.createTempFile(cacheDirectory, "oauth-", ".tmp")
        try {
            Files.writeString(tmp, gson.toJson(credentials), StandardOpenOption.TRUNCATE_EXISTING)
            runCatching {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            try {
                Files.move(tmp, credentialCacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, credentialCacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun newCredentials(clientId: String, refreshToken: String?, token: TokenResponse): OAuthCredentials {
        val expiresAt = token.expiresIn?.let { expiresIn ->
            (System.currentTimeMillis() / 1000) + (expiresIn - EXPIRY_SKEW_SECONDS).coerceAtLeast(0)
        }
        return OAuthCredentials(clientId, refreshToken, token.bearerAccessToken(), expiresAt)
    }

    private fun TokenResponse.bearerAccessToken(): String {
        if (!tokenType.equals("Bearer", ignoreCase = true)) {
            throw PaperweightException("OAuth token response did not identify a Bearer token.")
        }
        return accessToken.required("access_token")
    }

    private fun randomUrlSafeValue(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun randomPkceVerifier(): String {
        while (true) {
            val verifier = randomUrlSafeValue()
            if (sha256UrlSafe(verifier).first().isLetterOrDigit()) {
                return verifier
            }
        }
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
        val issuer: String?,
        @SerializedName("authorization_endpoint") val authorizationEndpoint: String?,
        @SerializedName("token_endpoint") val tokenEndpoint: String?,
        @SerializedName("registration_endpoint") val registrationEndpoint: String?,
        @SerializedName("response_types_supported") val responseTypesSupported: List<String>?,
        @SerializedName("grant_types_supported") val grantTypesSupported: List<String>?,
        @SerializedName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String>?,
        @SerializedName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>?,
    )

    private class ResourceMetadataResponse(
        val resource: String?,
        @SerializedName("authorization_servers") val authorizationServers: List<String>?,
    )

    private class OAuthConfiguration(
        val issuer: URI,
        val authorizationEndpoint: URI,
        val tokenEndpoint: URI,
        val registrationEndpoint: URI,
        val supportsRefresh: Boolean,
    )

    private class RegistrationResponse(@SerializedName("client_id") val clientId: String?)

    private class ErrorResponse(@SerializedName("error") val error: String?)

    private class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Long?,
        @SerializedName("token_type") val tokenType: String?,
    )

    private data class OAuthCredentials(
        val clientId: String,
        val refreshToken: String?,
        val accessToken: String,
        val expiresAt: Long?,
    ) {
        fun hasValidAccessToken(): Boolean = expiresAt == null || expiresAt > System.currentTimeMillis() / 1000
    }
}
