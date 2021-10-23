/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
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

import org.cadixdev.bombe.type.FieldType
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.Type
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.InnerClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.TopLevelClassMapping

/**
 * These reverse functions are exactly the same as [MappingSet.reverse],
 * except when mapping return types it uses the [MappingSet.deobfuscate] method
 * taking a [FieldType] rather than a type, as the one taking a [Type] calls the aforementioned
 * [FieldType] accepting method TWICE if the given [Type] is a [FieldType]. This causes issues when
 * you have mappings that look like this:
 *
 * - BinaryHeap -> Path
 * - Path -> PathEntity
 *
 * In this example, the standard [MappingSet.reverse] method would have mapped anything with PathEntity as return type to BinaryHeap!
 */

fun MappingSet.reverse2(): MappingSet =
    reverse2(MappingSet.create())

fun MappingSet.reverse2(parent: MappingSet): MappingSet {
    topLevelClassMappings.forEach { klass -> klass.reverse2(parent) }
    return parent
}

private fun TopLevelClassMapping.reverse2(parent: MappingSet): TopLevelClassMapping {
    val mapping = parent.createTopLevelClassMapping(deobfuscatedName, obfuscatedName)
    fieldMappings.forEach { field -> field.reverse(mapping) }
    methodMappings.forEach { method -> method.reverse2(mapping) }
    innerClassMappings.forEach { klass -> klass.reverse2(mapping) }
    return mapping
}

private fun InnerClassMapping.reverse2(parent: ClassMapping<*, *>): InnerClassMapping {
    val mapping = parent.createInnerClassMapping(deobfuscatedName, obfuscatedName)
    fieldMappings.forEach { field -> field.reverse(mapping) }
    methodMappings.forEach { method -> method.reverse2(mapping) }
    innerClassMappings.forEach { klass -> klass.reverse2(mapping) }
    return mapping
}

private fun MethodMapping.reverse2(parent: ClassMapping<*, *>): MethodMapping {
    val mapping: MethodMapping = parent.createMethodMapping(
        MethodSignature(deobfuscatedName, mappings.deobfuscate2(descriptor)),
        obfuscatedName
    )
    parameterMappings.forEach { param -> param.reverse(mapping) }
    return mapping
}

private fun MappingSet.deobfuscate2(descriptor: MethodDescriptor): MethodDescriptor {
    return MethodDescriptor(
        descriptor.paramTypes.map { type -> deobfuscate(type) },
        (descriptor.returnType as? FieldType)
            ?.let { deobfuscate(it) }
            ?: deobfuscate(descriptor.returnType)
    )
}
