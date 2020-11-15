/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
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

import io.papermc.paperweight.util.convertToFile
import io.papermc.paperweight.util.convertToUrl
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
import java.io.File
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

abstract class DownloadService : BuildService<BuildServiceParameters.None>, AutoCloseable {

    private val httpClient: CloseableHttpClient = HttpClientBuilder.create().let { builder ->
        builder.setRetryHandler { _, count, _ -> count < 3 }
        builder.useSystemProperties()
        builder.build()
    }

    fun download(source: Any, target: Any) {
        val url = source.convertToUrl()
        val file = target.convertToFile()
        download(url, file)
    }

    private fun download(source: URL, target: File) {
        target.parentFile.mkdirs()

        val etagFile = target.resolveSibling(target.name + ".etag")
        val etag = if (etagFile.exists()) etagFile.readText() else null

        val host = HttpHost(source.host, source.port, source.protocol)
        val time = if (target.exists()) target.lastModified() else 0

        val httpGet = HttpGet(source.file)
        // Super high timeout, reduce chances of weird things going wrong
        val timeouts = TimeUnit.MINUTES.toMillis(5).toInt()

        httpGet.config = RequestConfig.custom()
            .setConnectTimeout(timeouts)
            .setConnectionRequestTimeout(timeouts)
            .setSocketTimeout(timeouts)
            .setCookieSpec(CookieSpecs.STANDARD)
            .build()

        if (time > 0) {
            val value = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC))
            httpGet.setHeader("If-Modified-Since", value)
        }
        if (etag != null) {
            httpGet.setHeader("If-None-Match", etag)
        }

        httpClient.execute(host, httpGet).use { response ->
            val code = response.statusLine.statusCode
            if ((code < 200 || code > 299) && code != HttpStatus.SC_NOT_MODIFIED) {
                val reason = response.statusLine.reasonPhrase
                throw PaperweightException("Download failed, HTTP code: $code; URL: $source; Reason: $reason")
            }

            val lastModified = handleResponse(response, time, target)
            saveEtag(response, lastModified, target, etagFile)
        }
    }

    private fun handleResponse(response: CloseableHttpResponse, time: Long, target: File): Long {
        val lastModified = with(response.getLastHeader("Last-Modified")) {
            if (this == null) {
                return@with 0
            }
            if (value.isNullOrBlank()) {
                return@with 0
            }
            val date = DateUtils.parseDate(value) ?: return@with 0
            return@with date.time
        }
        if (response.statusLine.statusCode == HttpStatus.SC_NOT_MODIFIED) {
            if (lastModified != 0L && time >= lastModified) {
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

    private fun saveEtag(response: CloseableHttpResponse, lastModified: Long, target: File, etagFile: File) {
        if (lastModified > 0) {
            target.setLastModified(lastModified)
        }

        val header = response.getFirstHeader("ETag") ?: return
        val etag = header.value

        etagFile.writeText(etag)
    }

    override fun close() {
        httpClient.close()
    }
}
