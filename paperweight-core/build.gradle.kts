plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    shade(project(projects.paperweightLib.path, "sharedRuntime"))

    implementation(libs.bundles.kotson)
    implementation(libs.coroutines)
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper"
        implementationClass = "io.papermc.paperweight.core.PaperweightCore"
    }
}

val finalJar = tasks.register("finalJar", Zip::class) {
    archiveExtension.set("jar")
    from(zipTree(tasks.shadowJar.flatMap { it.archiveFile }))
}
tasks.assemble {
    dependsOn(finalJar)
}
val finalRuntimeElements by configurations.registering {
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
    compatibilityAttributes(objects)
    outgoing.artifact(finalJar)
}
val javaComponent = project.components.getByName("java") as AdhocComponentWithVariants
afterEvaluate {
    javaComponent.withVariantsFromConfiguration(configurations.shadowRuntimeElements.get()) {
        skip()
    }
}
javaComponent.addVariantsFromConfiguration(finalRuntimeElements.get()) {
    mapToMavenScope("runtime")
}
