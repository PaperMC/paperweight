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

fun writeParamNames(names: Map<String, Array<String?>>, file: File) {
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

fun parseParamNames(file: File): Map<String, Array<String?>> {
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
