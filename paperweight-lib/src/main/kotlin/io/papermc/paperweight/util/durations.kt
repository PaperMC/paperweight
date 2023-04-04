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

package io.papermc.paperweight.util

import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Map of accepted abbreviation [Char]s to [ChronoUnit].
 */
private val units = mapOf(
    'd' to ChronoUnit.DAYS,
    'h' to ChronoUnit.HOURS,
    'm' to ChronoUnit.MINUTES,
    's' to ChronoUnit.SECONDS
)

/**
 * Parses a [Duration] from [input].
 *
 * Accepted format is a number followed by a unit abbreviation.
 * See [units] for possible units.
 * Example input strings: `["1d", "12h", "1m", "30s"]`
 *
 * @param input formatted input string
 * @throws InvalidDurationException when [input] is improperly formatted
 */
@Throws(InvalidDurationException::class)
fun parseDuration(input: String): Duration {
    if (input.isBlank()) {
        throw InvalidDurationException.noInput(input)
    }
    if (input.length < 2) {
        throw InvalidDurationException.invalidInput(input)
    }
    val unitAbbreviation = input.last()

    val unit = units[unitAbbreviation] ?: throw InvalidDurationException.invalidInput(input)

    val length = try {
        input.substring(0, input.length - 1).toLong()
    } catch (ex: NumberFormatException) {
        throw InvalidDurationException.invalidInput(input, ex)
    }

    return Duration.of(length, unit)
}

private class InvalidDurationException private constructor(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause) {
    companion object {
        private val infoMessage = """
      Accepted format is a number followed by a unit abbreviation.
      Possible units: $units
      Example input strings: ["1d", "12h", "1m", "30s"]
        """.trimIndent()

        fun noInput(input: String): InvalidDurationException =
            InvalidDurationException("Cannot parse a Duration from a blank input string '$input'.\n$infoMessage")

        fun invalidInput(input: String, cause: Throwable? = null) =
            InvalidDurationException("Cannot parse a Duration from input '$input'.\n$infoMessage", cause)
    }
}
