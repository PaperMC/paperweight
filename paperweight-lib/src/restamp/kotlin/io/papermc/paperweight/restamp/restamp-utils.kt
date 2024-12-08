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

package io.papermc.paperweight.restamp

import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.*
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormat

fun atFromString(input: String): AccessTransform {
    var last = input.length - 1

    val final = if (input[last] == 'f') {
        if (input[--last] == '-') ModifierChange.REMOVE else ModifierChange.ADD
    } else {
        ModifierChange.NONE
    }

    val access = when (input.split("+", "-").first()) {
        "public" -> AccessChange.PUBLIC
        "protected" -> AccessChange.PROTECTED
        "private" -> AccessChange.PRIVATE
        else -> AccessChange.NONE
    }

    return AccessTransform.of(access, final)
}

// TODO: Don't copy paste this somehow
// Start copied from at.kt
fun AccessTransformFormat.writeLF(path: Path, at: AccessTransformSet, header: String = "") {
    val stringWriter = StringWriter()
    val writer = BufferedWriter(stringWriter)
    write(writer, at)
    writer.close()
    // unify line endings
    val lines = stringWriter.toString().replace("\r\n", "\n").split("\n")
    // remove last empty line, sort, then add it back
    path.writeText(lines.subList(0, lines.size - 1).sorted().joinToString("\n", header, "\n"), Charsets.UTF_8)
}
// End copied from at.kt
