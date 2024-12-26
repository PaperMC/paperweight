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
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.jvm.isAccessible
import kotlin.system.measureNanoTime
import org.gradle.api.logging.Logging

class WorkDispatcherImpl(private val work: Path) : WorkDispatcher {
    companion object {
        private val logger = Logging.getLogger(WorkDispatcherImpl::class.java)
    }

    private val provided = mutableSetOf<Value<*>>()
    private val registrations = mutableSetOf<Registration>()
    private var terminalInputHash: String? = null

    data class Registration(
        val name: String,
        val action: WorkDispatcher.Action,
        val inputs: List<Value<*>>,
        val outputs: List<Value<*>>,
    )

    override fun outputFile(name: String): FileValue = LazyFileValue(name)

    override fun outputDir(name: String): DirectoryValue = LazyDirectoryValue(name)

    override fun provided(value: Value<*>) {
        if (registrations.any { it.outputs.contains(value) }) {
            throw PaperweightException("Value $value is an output of a registered work unit")
        }
        if (!provided.add(value)) {
            throw PaperweightException("Value $value has already been provided")
        }
    }

    override fun <T : WorkDispatcher.Action> register(name: String, workUnit: T): T {
        if (registrations.any { it.name == name }) {
            throw PaperweightException("Work unit with name $name has already been registered")
        }
        val data = Registration(
            name,
            workUnit,
            workUnit::class.collectAnnotatedDeclaredProperties<Input>().extractValues(workUnit),
            workUnit::class.collectAnnotatedDeclaredProperties<Output>().extractValues(workUnit),
        )
        if (data.outputs.any { it in provided }) {
            throw PaperweightException("Output of work unit $name has already been provided")
        }
        registrations.add(data)
        return workUnit
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : WorkDispatcher.Action> registered(name: String): T {
        return registrations.firstOrNull { it.name == name }?.action as? T
            ?: throw PaperweightException("No work unit registered with name $name")
    }

    override fun overrideTerminalInputHash(hash: String) {
        terminalInputHash = hash
    }

    override fun dispatch(vararg targets: Value<*>, progressEventListener: (String) -> Unit) {
        val targetSet = targets.toSet()
        val start = System.nanoTime()
        val graph = WorkGraph(
            provided.toSet(),
            registrations.toSet(),
            terminalInputHash,
            targetSet,
        )
        val took = System.nanoTime() - start
        logger.info("Graph building for $targetSet took ${formatNs(took)}")
        val executedIn = measureNanoTime {
            graph.execute(work, progressEventListener)
        }
        logger.info("Execution of $targetSet took ${formatNs(executedIn)}")
    }

    private fun List<KProperty1<Any, *>>.extractValues(action: WorkDispatcher.Action): List<Value<*>> {
        return mapNotNull {
            if (!Value::class.isSuperclassOf(it.returnType.classifier as KClass<*>)) {
                throw PaperweightException("Input/output property $it does not return a Value")
            }
            it.get(action) as Value<*>?
        }
    }

    private inline fun <reified A : Annotation> KClass<*>.collectAnnotatedDeclaredProperties() =
        collectDeclaredProperties {
            it.annotations.any { a -> a is A }
        }

    @Suppress("unchecked_cast")
    private fun KClass<*>.collectDeclaredProperties(
        filter: (KProperty1<*, *>) -> Boolean
    ): List<KProperty1<Any, *>> =
        declaredMemberProperties.filter(filter).map {
            it.isAccessible = true
            it as KProperty1<Any, *>
        }
}
