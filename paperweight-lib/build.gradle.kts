plugins {
    id("config-kotlin")
    id("net.kyori.blossom") version "2.2.0"
}

repositories {
    gradlePluginPortal()
}

sourceSets.main {
    blossom {
        kotlinSources {
            properties.put("jst_version", libs.versions.jst)
            properties.put("tinyRemapper_version", libs.versions.tinyRemapper)
            properties.put("checkstyle_version", libs.versions.checkstyle)
        }
    }
}

dependencies {
    implementation(libs.httpclient5)
    implementation(libs.bundles.kotson)
    implementation(libs.coroutines)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)
    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.jbsdiff)

    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }

    testImplementation(libs.jgit)
    testImplementation(libs.mockk)
}

configurations.consumable("sourcesJar") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, named(DocsType.SOURCES))
    }
    outgoing.artifact(tasks.sourcesJar)
}

val testClassesJar = tasks.register<Jar>("testClassesJar") {
    archiveClassifier.set("test-classes")
    from(sourceSets.test.map { it.output.classesDirs })
    dependsOn(sourceSets.test.get().classesTaskName)
}
configurations.consumable("testClassesJar") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, named(LibraryElements.JAR))
    }
    outgoing.artifact(testClassesJar)
}
