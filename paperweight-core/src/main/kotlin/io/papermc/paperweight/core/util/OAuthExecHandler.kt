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

import io.papermc.paperweight.PaperweightException
import java.net.URI
import org.apache.hc.client5.http.auth.ChallengeType
import org.apache.hc.client5.http.classic.ExecChain
import org.apache.hc.client5.http.classic.ExecChainHandler
import org.apache.hc.client5.http.impl.auth.AuthChallengeParser
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.message.ParserCursor

/** Adds OAuth authentication to protected-resource requests and recovers once from auth challenges. */
internal class OAuthExecHandler(private val oauthClient: OAuthClient) : ExecChainHandler {
    override fun execute(
        request: ClassicHttpRequest,
        scope: ExecChain.Scope,
        chain: ExecChain,
    ): ClassicHttpResponse {
        oauthClient.tryAccessToken()?.let { request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        val response = chain.proceed(request, scope)

        val challenge = challengeOf(response) ?: return response
        response.close()
        val recovered = recover(request, scope, chain, challenge)

        // A second refusal after recovery means our grants do not cover what the resource demands.
        val repeated = challengeOf(recovered)
        if (repeated?.error == INSUFFICIENT_SCOPE) {
            recovered.close()
            throw insufficientScope(repeated)
        }
        return recovered
    }

    /**
     * Acquires a fresh token for the challenged resource and retries the request once. Scope
     * shortfalls request the scopes named by the challenge; any other 401 drops the cached token
     * so it is refreshed or re-authorized.
     */
    private fun recover(
        request: ClassicHttpRequest,
        scope: ExecChain.Scope,
        chain: ExecChain,
        challenge: Challenge,
    ): ClassicHttpResponse {
        val required = if (challenge.error == INSUFFICIENT_SCOPE) challenge.requiredScopes else emptySet()
        // forceRefresh discards the token the resource just refused.
        val token = oauthClient.accessToken(oauthClient.discoveryUri(challenge.resourceMetadataUri), required, forceRefresh = true)
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        // A 401 is received before resource processing, and StringEntity request bodies are repeatable.
        return chain.proceed(request, scope)
    }

    /** Returns the bearer challenge of a response, or null when the failure is unrelated to OAuth. */
    private fun challengeOf(response: ClassicHttpResponse): Challenge? {
        if (response.code != 401 && response.code != 403) {
            return null
        }
        val challenge = parseChallenge(response)
        if (response.code == 403 && challenge.error != INSUFFICIENT_SCOPE) {
            return null
        }
        return challenge
    }

    private fun insufficientScope(challenge: Challenge): PaperweightException {
        val scopes = challenge.requiredScopes.sorted()
        val listed = if (scopes.isEmpty()) "" else " Required scopes: ${scopes.joinToString(" ")}."
        return PaperweightException(
            "Access denied due to insufficient OAuth scope.$listed Your account may not be entitled to this operation."
        )
    }

    private fun parseChallenge(response: ClassicHttpResponse): Challenge {
        val bearer = response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)
            .asSequence()
            .flatMap { header ->
                runCatching {
                    AuthChallengeParser.INSTANCE.parse(ChallengeType.TARGET, header.value, ParserCursor(0, header.value.length))
                }.getOrDefault(emptyList()).asSequence()
            }
            .firstOrNull { it.schemeName.equals(BEARER, ignoreCase = true) }
            ?: return Challenge()
        val attributes = bearer.params.associate { it.name.lowercase() to it.value }
        val resourceMetadata = attributes["resource_metadata"]
            ?.let { value -> runCatching { URI.create(value) }.getOrNull() }
        val requiredScopes = attributes["scope"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet().orEmpty()
        return Challenge(attributes["error"] ?: "invalid_token", resourceMetadata, requiredScopes)
    }

    private data class Challenge(
        val error: String = "invalid_token",
        val resourceMetadataUri: URI? = null,
        val requiredScopes: Set<String> = emptySet(),
    )

    private companion object {
        const val BEARER = "Bearer"
        const val INSUFFICIENT_SCOPE = "insufficient_scope"
    }
}
