package io.papermc.paperweight.core.extension

import io.papermc.paperweight.util.constants.*
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.*

open class McpConfigExtension(objects: ObjectFactory, layout: ProjectLayout) {

    val artifact: Property<String> = objects.property()
    val repo: Property<String> = objects.property<String>().convention(FORGE_MAVEN_REPO_URL)
}
