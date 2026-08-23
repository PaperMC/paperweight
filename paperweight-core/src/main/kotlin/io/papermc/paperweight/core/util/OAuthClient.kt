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
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder
import org.gradle.api.logging.Logging

/**
 * OAuth client for one protected resource.
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
internal class OAuthClient(
    private val httpClient: CloseableHttpClient,
    private val resourceUri: URI,
    private val cacheDirectory: Path,
) {
    private val resourceKey = sha256UrlSafe(resourceUri.toString())
    private val configuration by lazy(::discoverConfiguration)
    private var credentials: OAuthCredentials? = null

    /**
     * Returns a usable access token. When [forceRefresh] is true, a cached access
     * token is discarded after a protected resource has rejected it.
     */
    fun accessToken(forceRefresh: Boolean = false): String {
        val currentCredentials = credentials ?: loadCredentials(configuration.issuer).also { credentials = it }
        if (!forceRefresh && currentCredentials?.hasValidAccessToken() == true) {
            return currentCredentials.accessToken
        }
        if (currentCredentials != null) {
            refreshAccessToken(configuration, currentCredentials)?.let { return it }
            logger.lifecycle("Cached OAuth credentials for $resourceUri are invalid or expired.")
        }
        return authorize(configuration)
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
            issuer.toString(),
            secureEndpoint(metadata.authorizationEndpoint.required("authorization_endpoint"), "authorization_endpoint"),
            secureEndpoint(metadata.tokenEndpoint.required("token_endpoint"), "token_endpoint"),
            secureEndpoint(metadata.registrationEndpoint.required("registration_endpoint"), "registration_endpoint"),
            "refresh_token" in grantTypes,
        )
    }

    private fun fetchJson(uri: URI, description: String): String =
        httpClient.execute(ClassicRequestBuilder.get(uri).build()) { response ->
            val body = response.entity?.let(EntityUtils::toString).orEmpty()
            if (response.code !in 200..299) {
                throw PaperweightException("Could not fetch $description: ${response.code} $body")
            }
            val contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE)?.value
            if (contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) != true) {
                throw PaperweightException("$description did not use the application/json content type.")
            }
            body
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

    private fun secureEndpoint(value: String, field: String): String {
        validateHttps(URI.create(value), "OAuth $field")
        return value
    }

    private fun origin(uri: URI): String = "${uri.scheme}://${uri.rawAuthority}"

    private fun authorize(configuration: OAuthConfiguration): String {
        val verifier = randomUrlSafeValue()
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
            saveCredentials(configuration.issuer, credentials)
            logger.lifecycle("OAuth authorization succeeded for $resourceUri.")
            return credentials.accessToken
        } finally {
            server.stop(0)
        }
    }

    private fun refreshAccessToken(configuration: OAuthConfiguration, credentials: OAuthCredentials): String? {
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
        val token = httpClient.execute(request) { response ->
            val responseBody = response.entity?.let(EntityUtils::toString).orEmpty()
            if (response.code in 400..499) {
                val error = runCatching { gson.fromJson(responseBody, ErrorResponse::class.java).error }.getOrNull()
                if (error == "invalid_grant" || error == "invalid_client") {
                    return@execute null
                }
            }
            if (response.code !in 200..299) {
                throw PaperweightException("Could not refresh OAuth access token: ${response.code} $responseBody")
            }
            gson.fromJson(responseBody, TokenResponse::class.java)
        } ?: return null
        val refreshed = newCredentials(credentials.clientId, token.refreshToken ?: credentials.refreshToken, token)
        saveCredentials(configuration.issuer, refreshed)
        return refreshed.accessToken
    }

    private fun authorizationUri(
        authorizationEndpoint: String,
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
        val query = URIBuilder(exchange.requestURI).queryParams.associate { it.name to it.value }
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

    private fun registerClient(registrationEndpoint: String, redirectUri: URI, supportsRefresh: Boolean): String {
        val grantTypes = listOfNotNull("authorization_code", "refresh_token".takeIf { supportsRefresh })
        val registrationRequest = mapOf(
            "redirect_uris" to listOf(redirectUri.toString()),
            "grant_types" to grantTypes,
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to "none",
        )
        val body = gson.toJson(registrationRequest)
        return httpClient.execute(
            ClassicRequestBuilder.post(registrationEndpoint)
                .setEntity(body, ContentType.APPLICATION_JSON)
                .build()
        ) { response ->
            val responseBody = response.entity?.let(EntityUtils::toString).orEmpty()
            if (response.code !in 200..299) {
                throw PaperweightException(
                    "Could not register an OAuth client: ${response.code} $responseBody. " +
                        "For Cloudflare Access, enable allow loopback clients for this application."
                )
            }
            gson.fromJson(responseBody, RegistrationResponse::class.java).clientId.required("client_id")
        }
    }

    private fun exchangeCode(
        tokenEndpoint: String,
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
        return httpClient.execute(request) { response ->
            val responseBody = response.entity?.let(EntityUtils::toString).orEmpty()
            if (response.code !in 200..299) {
                throw PaperweightException("Could not exchange OAuth authorization code: ${response.code} $responseBody")
            }
            gson.fromJson(responseBody, TokenResponse::class.java)
        }
    }

    private fun formRequest(tokenEndpoint: String, vararg parameters: Pair<String, String>): ClassicHttpRequest =
        ClassicRequestBuilder.post(tokenEndpoint)
            .setEntity(UrlEncodedFormEntity(parameters.map { BasicNameValuePair(it.first, it.second) }))
            .build()

    private fun loadCredentials(issuer: String): OAuthCredentials? {
        val cacheFile = cacheFile(issuer)
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
        this.credentials = credentials
    }

    private fun cacheFile(issuer: String): Path =
        cacheDirectory.resolve("oauth-$resourceKey-${sha256UrlSafe(issuer)}.json")

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
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String,
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

    private companion object {
        private val logger = Logging.getLogger(OAuthClient::class.java)

        const val CALLBACK_PATH = "/oauth/callback"
        const val EXPIRY_SKEW_SECONDS = 30L
    }
}
