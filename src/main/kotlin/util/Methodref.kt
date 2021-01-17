package io.papermc.paperweight.util

import org.cadixdev.bombe.type.signature.MethodSignature
import org.jgrapht.Graph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import org.jgrapht.nio.dot.DOTImporter
import org.jgrapht.nio.gml.GmlExporter
import org.jgrapht.nio.gml.GmlImporter
import org.jgrapht.nio.json.JSONExporter
import org.jgrapht.nio.json.JSONImporter

data class MethodRef(val className: String, val methodName: String, val methodDesc: String) {
    companion object {
        fun from(className: String, sig: MethodSignature): MethodRef {
            return MethodRef(className, sig.name, sig.descriptor.toString())
        }
    }
}

fun writeOverrides(graph: Graph<MethodRef, DefaultEdge>, file: Any) {
    val exporter = JSONExporter<MethodRef, DefaultEdge> { ref -> "${ref.className} ${ref.methodName} ${ref.methodDesc}" }
    file.convertToFile().bufferedWriter().use { writer ->
        exporter.exportGraph(graph, writer)
    }
}

fun readOverrides(file: Any): SimpleDirectedGraph<MethodRef, *> {
    val importer = JSONImporter<MethodRef, DefaultEdge>()
    importer.setVertexFactory {
        val (className, methodName, methodDesc) = it.split(" ")
        MethodRef(className, methodName, methodDesc)
    }
    val overrides = SimpleDirectedGraph<MethodRef, DefaultEdge>(DefaultEdge::class.java)
    file.convertToFile().bufferedReader().use { reader ->
        importer.importGraph(overrides, reader)
    }

    return overrides
}
