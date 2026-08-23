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

package io.papermc.paperweight

import io.papermc.paperweight.util.*
import java.net.URL
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.*
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.utils.DateUtils
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.HttpStatus
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class DownloadService : BuildService<DownloadService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        val projectPath: DirectoryProperty
    }

    private companion object {
        val LOGGER: Logger = Logging.getLogger(DownloadService::class.java)
    }

    // Allow 24 parallel downloads, up to 8 per route, and wait up to five minutes for a connection or response data.
    private val httpClient: CloseableHttpClient =
        HttpClientBuilder.create()
            .setRetryStrategy(DefaultHttpRequestRetryStrategy(2, TimeValue.ofSeconds(1)))
            .useSystemProperties()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMinutes(5))
                    .setResponseTimeout(Timeout.ofMinutes(5))
                    .build()
            )
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .useSystemProperties()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(30))
                            .build()
                    )
                    .setMaxConnTotal(24)
                    .setMaxConnPerRoute(8)
                    .build()
            )
            .build()

    fun download(source: Any, target: Any, hash: Hash? = null) {
        val url = source.convertToUrl()
        val file = target.convertToPath()
        download(url, file, hash)
    }

    private fun download(source: URL, target: Path, hash: Hash?, retry: Boolean = false) {
        download(source, target)
        if (hash == null) {
            return
        }
        val dlHash = target.hashFile(hash.algorithm).asHexString().lowercase(Locale.ENGLISH)
        if (hash.value == "" || dlHash == hash.valueLower) {
            return
        }
        LOGGER.warn(
            "{} hash of downloaded file '{}' does not match what was expected! (expected: '{}', got: '{}')",
            hash.algorithm.name,
            target,
            hash.valueLower,
            dlHash
        )
        if (retry) {
            throw PaperweightException("Failed to download file '$target' from '$source'.")
        }
        LOGGER.warn("Re-attempting download once before giving up.")
        target.deleteIfExists()
        download(source, target, hash, true)
    }

    private fun download(source: URL, target: Path) {
        target.parent.createDirectories()

        if (source.protocol == "file") {
            var path = source.toString().replace("file://", "")
            if (source.host == "project") {
                path = path.replace("project", parameters.projectPath.path.absolutePathString())
            }
            Path.of(path).copyTo(target, overwrite = true)
            return
        }

        val etagDir = target.resolveSibling("etags")
        etagDir.createDirectories()

        val etagFile = etagDir.resolve(target.name + ".etag")
        val etag = if (etagFile.exists()) etagFile.readText() else null

        val time = if (target.exists()) target.getLastModifiedTime().toInstant() else Instant.EPOCH

        val httpGet = HttpGet(source.toString())

        if (target.exists()) {
            if (time != Instant.EPOCH) {
                val value = DateTimeFormatter.RFC_1123_DATE_TIME.format(time.atZone(ZoneOffset.UTC))
                httpGet.setHeader("If-Modified-Since", value)
            }
            if (etag != null) {
                httpGet.setHeader("If-None-Match", etag)
            }
        }

        httpClient.execute(httpGet) { response ->
            val code = response.code
            if (code !in 200..299 && code != HttpStatus.SC_NOT_MODIFIED) {
                throw PaperweightException("Download failed, HTTP code: $code; URL: $source; Reason: ${response.reasonPhrase}")
            }

            val lastModified = handleResponse(response, target)
            saveEtag(response, lastModified, target, etagFile)
        }
    }

    private fun handleResponse(response: ClassicHttpResponse, target: Path): Instant {
        val lastModified = DateUtils.parseStandardDate(response, "Last-Modified") ?: Instant.EPOCH
        if (response.code == HttpStatus.SC_NOT_MODIFIED) {
            return lastModified
        }

        val entity = response.entity ?: return lastModified
        target.outputStream().use { output ->
            entity.content.use { input ->
                input.copyTo(output)
            }
        }

        return lastModified
    }

    private fun saveEtag(response: ClassicHttpResponse, lastModified: Instant, target: Path, etagFile: Path) {
        if (lastModified != Instant.EPOCH) {
            target.setLastModifiedTime(FileTime.from(lastModified))
        }

        val header = response.getFirstHeader("ETag") ?: return
        val etag = header.value

        etagFile.writeText(etag)
    }

    override fun close() {
        httpClient.close()
    }
}
