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

package io.papermc.paperweight.tasks.sourceremap

import java.io.File

data class ConstructorsData(val constructors:  Map<String, List<ConstructorNode>>)

data class ConstructorNode(
    val id: Int,
    val descriptor: String
)

fun parseConstructors(constructors: File): ConstructorsData {
    val constructorMap = hashMapOf<String, MutableList<ConstructorNode>>()

    constructors.useLines { lines ->
        lines.forEach { line ->
            val parts = line.split(' ')
            constructorMap.compute(parts[1]) { _, v ->
                val node = ConstructorNode(parts[0].toInt(), parts[2])
                if (v == null) {
                    return@compute mutableListOf(node)
                } else {
                    v += node
                    return@compute v
                }
            }
        }
    }

    for (list in constructorMap.values) {
        // Put bigger numbers first
        // Old constructor entries are still present, just with smaller numbers. So we don't want to grab an older
        // entry
        list.reverse()
    }

    return ConstructorsData(constructorMap)
}

typealias ParamNames = MutableMap<String, Array<String?>>
fun newParamNames(): ParamNames = mutableMapOf()

fun writeParamNames(names: ParamNames, file: File) {
    file.bufferedWriter().use { writer ->
        for ((desc, params) in names.entries) {
            writer.append(desc).append(' ')
            for (i in params.indices) {
                writer.append(i.toString()).append(' ').append(params[i])
                if (i != params.lastIndex) {
                    writer.append(' ')
                }
            }
            writer.newLine()
        }
    }
}

fun parseParamNames(file: File): ParamNames {
    val paramNames: MutableMap<String, Array<String?>> = mutableMapOf()
    file.useLines { lines ->
        for (line in lines) {
            val parts = line.split(' ')
            val params = parts.asSequence().drop(1).chunked(2).associate { it[0].toInt() to it[1] }
            paramNames[parts.first()] = Array(params.size) { params.getValue(it) }
        }
    }
    return paramNames
}
