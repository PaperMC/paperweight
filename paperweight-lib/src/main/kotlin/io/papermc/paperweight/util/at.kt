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

import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.*
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormat
import org.cadixdev.at.io.AccessTransformFormats

fun atFromString(input: String): AccessTransform {
    var last = input.length - 1

    val final: ModifierChange
    if (input[last] == 'f') {
        final = if (input[--last] == '-') ModifierChange.REMOVE else ModifierChange.ADD
    } else {
        final = ModifierChange.NONE
    }

    val access = when (input.substring(0, last)) {
        "public" -> AccessChange.PUBLIC
        "protected" -> AccessChange.PROTECTED
        "private" -> AccessChange.PRIVATE
        else -> AccessChange.NONE
    }

    println("input = $input")
    println(input.substring(0, last))
    println("access = $access, final = $final")

    return AccessTransform.of(access, final)
}

fun atToString(at: AccessTransform): String {
    val access = when (at.access) {
        AccessChange.PRIVATE -> "private"
        AccessChange.PROTECTED -> "protected"
        AccessChange.PUBLIC -> "public"
        else -> ""
    }
    val final = when (at.final) {
        ModifierChange.REMOVE -> "-f"
        ModifierChange.ADD -> "+f"
        else -> ""
    }
    return access + final
}

fun AccessTransformFormat.writeLF(path: Path, at: AccessTransformSet) {
    val stringWriter = StringWriter()
    val writer = BufferedWriter(stringWriter)
    AccessTransformFormats.FML.write(writer, at)
    writer.close()
    path.writeText(stringWriter.toString().replace("\r\n", "\n"), Charsets.UTF_8)
}
