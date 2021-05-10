package io.papermc.paperweight.transform

import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.*

class TransformBuilder(private val project: Project, type: String, state: String) {

    init {
        project.dependencies {
            attributesSchema {
                attribute(transformState)
            }
            if (artifactTypes.names.contains(type)) {
                artifactTypes.named(type) {
                    attributes.attribute(transformState, TRANSFORM_STATE_BASE)
                }
            } else {
                artifactTypes.register(type) {
                    attributes.attribute(transformState, TRANSFORM_STATE_BASE)
                }
            }
        }
        project.configurations.named("test") {
            attributes.attribute(transformState, state)
        }
    }

    fun initialize() {
    }

    companion object {
        private val artifactType: Attribute<String> = attributeOf("artifactType")
        private val transformState: Attribute<String> = attributeOf("io.papermc.paperweight.transformState")

        private const val TRANSFORM_STATE_BASE: String = "base"

        private inline fun <reified T> attributeOf(name: String) = Attribute.of(name, T::class.java)
    }
}
