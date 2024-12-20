plugins {
    `config-kotlin`
    id("net.kyori.blossom") version "2.1.0"
}

repositories {
    gradlePluginPortal()
}

sourceSets.main {
    blossom {
        kotlinSources {
            properties.put("jst_version", libs.versions.jst)
            properties.put("tinyRemapper_version", libs.versions.tinyRemapper)
        }
    }
}

dependencies {
    implementation(libs.httpclient)
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

val testClassesJar = tasks.register<Jar>("testClassesJar") {
    archiveClassifier.set("test-classes")
    from(sourceSets.test.get().output.classesDirs)
    dependsOn(sourceSets.test.get().classesTaskName)
}
configurations.consumable("testClassesJar") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
    outgoing.artifact(testClassesJar)
}
