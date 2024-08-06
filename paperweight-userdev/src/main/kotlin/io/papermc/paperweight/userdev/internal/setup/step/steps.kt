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

package io.papermc.paperweight.userdev.internal.setup.step

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.userdev.internal.setup.SetupHandler
import io.papermc.paperweight.userdev.internal.setup.UserdevSetup
import io.papermc.paperweight.userdev.internal.setup.util.HashFunction
import io.papermc.paperweight.userdev.internal.setup.util.HashFunctionBuilder
import io.papermc.paperweight.userdev.internal.setup.util.buildHashFunction
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.system.measureTimeMillis

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Input

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Output

interface SetupStep {
    val name: String

    val hashFile: Path

    fun run(context: SetupHandler.Context)

    fun touchHashFunctionBuilder(builder: HashFunctionBuilder) {}
}

object StepExecutor {
    private data class InputOutputData(
        val inputs: List<KProperty1<Any, *>>,
        val outputs: List<KProperty1<Any, *>>,
    )

    private val inputOutputDataCache: MutableMap<KClass<out SetupStep>, InputOutputData> =
        ConcurrentHashMap<KClass<out SetupStep>, InputOutputData>()

    fun executeSteps(expectingChange: Boolean, context: SetupHandler.Context, vararg steps: SetupStep) {
        // if we aren't expecting change, assume the last step is the output that matters
        // and only verify its inputs/outputs - if it fails then we need to go back through
        // and check each step anyway
        if (!expectingChange) {
            val lastStep = steps.last()
            if (makeHashFunction(lastStep).upToDate(lastStep.hashFile)) {
                return
            }
        }

        try {
            for (step in steps) {
                executeStep(context, step)
            }
        } catch (ex: Exception) {
            for (step in steps) {
                step.hashFile.deleteIfExists()
            }
            throw PaperweightException("Failed to execute steps, invalidated relevant caches. Run with --stacktrace for more details.", ex)
        }
    }

    fun executeStep(context: SetupHandler.Context, step: SetupStep) {
        val hashFunction = makeHashFunction(step)

        if (hashFunction.upToDate(step.hashFile)) {
            return
        }

        UserdevSetup.LOGGER.lifecycle(":executing '{}'", step.name)
        val elapsed = measureTimeMillis {
            step.run(context)
        }
        UserdevSetup.LOGGER.info("done executing '{}', took {}s", step.name, elapsed / 1000.00)

        hashFunction.writeHash(step.hashFile)
    }

    private fun makeHashFunction(step: SetupStep): HashFunction {
        val data = step.inputOutputData
        val inputs = data.inputs.mapNotNull { it.get(step) }
        val outputs = data.outputs.mapNotNull { it.get(step) }

        return buildHashFunction(inputs, outputs) {
            step.touchHashFunctionBuilder(this)
        }
    }

    private val SetupStep.inputOutputData: InputOutputData
        get() = inputOutputDataCache.computeIfAbsent(this::class) {
            InputOutputData(
                it.collectAnnotatedDeclaredProperties<Input>(),
                it.collectAnnotatedDeclaredProperties<Output>(),
            )
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
