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

package io.papermc.paperweight.userdev.internal.action

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.util.formatNs
import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.io.path.*
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class WorkGraph(
    private val provided: Set<Value<*>>,
    private val registrations: Set<WorkDispatcherImpl.Registration>,
    private val terminalInputHash: String?,
    private val requested: Set<Value<*>>,
) {
    companion object {
        private val logger = Logging.getLogger(WorkGraph::class.java)
        const val METADATA_FILE = "metadata.json"
    }

    class Node(
        val registration: WorkDispatcherImpl.Registration,
        val dependencies: List<Node>,
        var inputHash: String? = null,
    )

    private val roots: List<Node> = buildGraph(requested.toList())

    init {
        logger.printGraph(LogLevel.INFO, roots)
    }

    private fun Logger.printGraph(level: LogLevel, nodes: List<Node>, indent: String = "") {
        for (node in nodes) {
            logger.log(level, "$indent${node.registration.name}")
            printGraph(level, node.dependencies, "$indent\t")
        }
    }

    private fun buildGraph(
        requested: List<Value<*>>,
        nodeCache: MutableMap<WorkDispatcherImpl.Registration, Node> = IdentityHashMap(),
    ): List<Node> {
        val nodes = mutableListOf<Node>()
        for (req in requested) {
            if (provided.contains(req)) {
                continue
            }
            val producers = registrations.filter { it.outputs.contains(req) }
            if (producers.isEmpty()) {
                throw PaperweightException("No producer or provider for value $req")
            }
            if (producers.size > 1) {
                throw PaperweightException("Multiple producers for value $req")
            }
            val producer = producers.single()
            val node = nodeCache[producer] ?: Node(producer, buildGraph(producer.inputs, nodeCache)).also {
                // This is only used as debug information
                it.registration.outputs.forEach { output ->
                    if (output is FileSystemLocationOutputValue) {
                        output.owner = it.registration.name
                    }
                }
            }
            nodeCache[producer] = node
            nodes += node
        }
        return nodes
    }

    fun execute(work: Path, progressEventListener: (String) -> Unit = {}) {
        val visited = mutableSetOf<Node>()
        val hashCache = mutableMapOf<Value<*>, String>()
        for (node in roots) {
            executeNode(work, node, visited, hashCache, progressEventListener)
        }
    }

    data class Metadata(
        val outputHashes: List<String>,
        val skippedWhenUpToDate: Set<String>?,
        val lastUsed: Long = System.currentTimeMillis(),
    ) {
        fun updateLastUsed() = copy(lastUsed = System.currentTimeMillis())

        fun writeTo(file: Path) {
            file.createParentDirectories().bufferedWriter().use { gson.toJson(this, it) }
        }
    }

    private fun executeNode(
        work: Path,
        node: Node,
        visited: MutableSet<Node>,
        hashCache: MutableMap<Value<*>, String>,
        progressEventListener: (String) -> Unit,
        retry: Boolean = false,
    ) {
        val root = node.registration.outputs.any { it in requested }

        if (!retry && !visited.add(node)) {
            return
        }

        val earlyUpToDateCheck = root && !retry && terminalInputHash != null

        if (!earlyUpToDateCheck) {
            for (dep in node.dependencies) {
                executeNode(work, dep, visited, hashCache, progressEventListener)
            }
        }

        progressEventListener(node.registration.name)

        val start = System.nanoTime()
        val inputHash = if (terminalInputHash != null && root) {
            terminalInputHash
        } else {
            val inputHashes = node.registration.inputs.hash(hashCache)
            inputHashes.map { InputStreamProvider.wrap(it.byteInputStream()) }
                .hash(HashingAlgorithm.SHA256)
                .asHexString()
        }
        node.inputHash = inputHash

        realizeOutputPaths(node, work, inputHash)

        val lockFile = work.resolve("${node.registration.name}_$inputHash/lock")

        val metadataFile = work.resolve("${node.registration.name}_$inputHash/$METADATA_FILE")
        val upToDate = withLock(lockFile) {
            if (metadataFile.exists()) {
                val metadata = metadataFile.bufferedReader().use { gson.fromJson(it, Metadata::class.java) }
                if (node.registration.outputs.hash(hashCache) == metadata.outputHashes) {
                    logger.lifecycle("Skipping ${node.registration.name} (up-to-date)")
                    metadata.updateLastUsed().writeTo(metadataFile)
                    val took = System.nanoTime() - start
                    logger.info("Up-to-date check for ${node.registration.name} took ${formatNs(took)} (up-to-date)")
                    return@withLock true
                } else {
                    node.registration.outputs.forEach { hashCache.remove(it) }
                    val took = System.nanoTime() - start
                    logger.info("Up-to-date check for ${node.registration.name} took ${formatNs(took)} (out-of-date)")
                }
            }

            if (!earlyUpToDateCheck) {
                logger.lifecycle("Executing ${node.registration.name}...")
                val startExec = System.nanoTime()

                try {
                    node.registration.action.execute()
                } catch (e: Exception) {
                    throw PaperweightException("Exception executing ${node.registration.name}", e)
                }

                val deps = if (root && terminalInputHash != null) collectDependencies(node) else null
                val metadata = Metadata(node.registration.outputs.hash(hashCache), deps?.takeIf { it.isNotEmpty() })
                metadata.writeTo(metadataFile)

                val tookExec = System.nanoTime() - startExec
                logger.lifecycle("Finished ${node.registration.name} in ${formatNs(tookExec)}")
            }

            return@withLock false
        }
        if (upToDate) {
            return
        }

        if (earlyUpToDateCheck) {
            executeNode(work, node, visited, hashCache, progressEventListener, true)
        }
    }

    private fun collectDependencies(node: Node): Set<String> {
        val deps = mutableSetOf<String>()
        val nodes = mutableListOf<Node>()
        nodes.add(node)
        while (nodes.isNotEmpty()) {
            val n = nodes.removeAt(0)
            for (dep in n.dependencies) {
                nodes.add(dep)
                if (dep != node) {
                    deps += "${dep.registration.name}_${dep.inputHash}"
                }
            }
        }
        return deps
    }

    private fun List<Value<*>>.hash(cache: MutableMap<Value<*>, String>): List<String> {
        return map {
            cache.computeIfAbsent(it) { v ->
                v.bytes().hash(HashingAlgorithm.SHA256).asHexString()
            }
        }
    }

    private fun realizeOutputPaths(node: Node, work: Path, inputHash: String) {
        for (out in node.registration.outputs) {
            when (out) {
                is FileSystemLocationOutputValue -> out.path = work.resolve("${node.registration.name}_$inputHash/${out.name}")
                else -> throw PaperweightException("Unsupported output type ${out::class.java.name}")
            }
        }
    }
}
