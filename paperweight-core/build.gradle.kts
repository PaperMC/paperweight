plugins {
    `config-kotlin`
    `config-publish`
}

val restamp: Configuration by configurations.creating
configurations.implementation {
    extendsFrom(restamp)
}

dependencies {
    shade(projects.paperweightLib)
    shade(project(projects.paperweightLib.dependencyProject.path, "sharedRuntime"))
    restamp(project(projects.paperweightLib.dependencyProject.path, "restampRuntime"))

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
    from(zipTree(restamp.elements.map { it.single() })) {
        exclude("META-INF/MANIFEST.MF")
    }
}
val finalRuntimeElements by configurations.registering {
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
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
