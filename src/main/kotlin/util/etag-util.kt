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

package io.papermc.paperweight.util

import io.papermc.paperweight.shared.PaperweightException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11"

fun getWithEtag(urlText: String, cache: File, etagFile: File) {
    if (cache.exists() && cache.lastModified() + TimeUnit.MINUTES.toMillis(1) >= System.currentTimeMillis()) {
        return
    }

    val etag = if (etagFile.exists()) {
        etagFile.readText()
    } else {
        etagFile.parentFile.mkdirs()
        ""
    }

    var thrown: Throwable? = null

    try {
        val url = URL(urlText)

        val con = url.openConnection() as HttpURLConnection
        con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", USER_AGENT)
        con.ifModifiedSince = cache.lastModified()

        if (etag.isNotEmpty()) {
            con.setRequestProperty("If-None-Match", etag)
        }

        try {
            con.connect()

            when (con.responseCode) {
                304 -> {
                    cache.setLastModified(System.currentTimeMillis())
                    return
                }
                200 -> {
                    val data = con.inputStream.use { stream ->
                        stream.readBytes()
                    }

                    cache.writeBytes(data)

                    val newEtag = con.getHeaderField("ETag")
                    if (newEtag.isNullOrEmpty()) {
                        if (!etagFile.createNewFile()) {
                            etagFile.setLastModified(System.currentTimeMillis())
                        }
                    } else {
                        etagFile.writeText(newEtag)
                    }

                    return
                }
                else -> throw RuntimeException("Etag download for $urlText failed with code ${con.responseCode}")
            }
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        if (thrown == null) {
            thrown = e
        } else {
            thrown.addSuppressed(e)
        }
    }

    val errorString = "Unable to download from $urlText with etag"
    val ex = if (thrown != null) {
        PaperweightException(errorString, thrown)
    } else {
        PaperweightException(errorString)
    }
    throw ex
}
