/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2020 Kyle Wood (DemonWav)
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

import io.papermc.paperweight.util.convertToPath
import io.papermc.paperweight.util.convertToUrl
import java.net.URL
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import org.apache.http.HttpHost
import org.apache.http.HttpStatus
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.DateUtils
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class DownloadService : BuildService<BuildServiceParameters.None>, AutoCloseable {

    private val httpClient: CloseableHttpClient = HttpClientBuilder.create().let { builder ->
        builder.setRetryHandler { _, count, _ -> count < 3 }
        builder.useSystemProperties()
        builder.build()
    }

    fun download(source: Any, target: Any) {
        val url = source.convertToUrl()
        val file = target.convertToPath()
        download(url, file)
    }

    private fun download(source: URL, target: Path) {
        target.parent.createDirectories()

        val etagDir = target.resolveSibling("etags")
        etagDir.createDirectories()

        val etagFile = etagDir.resolve(target.name + ".etag")
        val etag = if (etagFile.exists()) etagFile.readText() else null

        val host = HttpHost(source.host, source.port, source.protocol)
        val time = if (target.exists()) target.getLastModifiedTime().toInstant() else Instant.EPOCH

        val httpGet = HttpGet(source.file)
        // high timeout, reduce chances of weird things going wrong
        val timeouts = TimeUnit.MINUTES.toMillis(5).toInt()

        httpGet.config = RequestConfig.custom()
            .setConnectTimeout(timeouts)
            .setConnectionRequestTimeout(timeouts)
            .setSocketTimeout(timeouts)
            .setCookieSpec(CookieSpecs.STANDARD)
            .build()

        if (time != Instant.EPOCH) {
            val value = DateTimeFormatter.RFC_1123_DATE_TIME.format(time.atZone(ZoneOffset.UTC))
            httpGet.setHeader("If-Modified-Since", value)
        }
        if (etag != null) {
            httpGet.setHeader("If-None-Match", etag)
        }

        httpClient.execute(host, httpGet).use { response ->
            val code = response.statusLine.statusCode
            if (code !in 200..299 && code != HttpStatus.SC_NOT_MODIFIED) {
                val reason = response.statusLine.reasonPhrase
                throw PaperweightException("Download failed, HTTP code: $code; URL: $source; Reason: $reason")
            }

            val lastModified = handleResponse(response, time, target)
            saveEtag(response, lastModified, target, etagFile)
        }
    }

    private fun handleResponse(response: CloseableHttpResponse, time: Instant, target: Path): Instant {
        val lastModified = with(response.getLastHeader("Last-Modified")) {
            if (this == null) {
                return@with Instant.EPOCH
            }
            if (value.isNullOrBlank()) {
                return@with Instant.EPOCH
            }
            return@with DateUtils.parseDate(value).toInstant() ?: Instant.EPOCH
        }
        if (response.statusLine.statusCode == HttpStatus.SC_NOT_MODIFIED) {
            if (lastModified != Instant.EPOCH && time >= lastModified) {
                return lastModified
            }
        }

        val entity = response.entity ?: return lastModified
        entity.content.use { input ->
            target.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }

        return lastModified
    }

    private fun saveEtag(response: CloseableHttpResponse, lastModified: Instant, target: Path, etagFile: Path) {
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
