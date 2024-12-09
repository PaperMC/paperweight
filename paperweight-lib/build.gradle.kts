plugins {
    `config-kotlin`
    id("net.kyori.blossom") version "2.1.0"
}

repositories {
    gradlePluginPortal()
}

val shared = sourceSets.create("shared")
val sharedJar by tasks.creating(Jar::class) {
    archiveClassifier = "shared"
    from(shared.output)
}
tasks.jar {
    from(shared.output)
}
val restamp = sourceSets.create("restamp") {
    blossom {
        kotlinSources {
            properties.put("restamp_version", libs.versions.restamp)
        }
    }
}
val restampJar by tasks.creating(Jar::class) {
    archiveClassifier = "restamp"
    from(restamp.output)
}

configurations {
    consumable("restampRuntime") {
        outgoing.artifact(restampJar)
    }
    consumable("sharedRuntime") {
        outgoing.artifact(sharedJar)
    }
}

dependencies {
    shared.compileOnlyConfigurationName(gradleApi())
    shared.compileOnlyConfigurationName(gradleKotlinDsl())
    compileOnly(shared.output)
    testImplementation(shared.output)

    restamp.implementationConfigurationName(libs.restamp)
    restamp.implementationConfigurationName(shared.output)
    restamp.compileOnlyConfigurationName(gradleApi())
    restamp.compileOnlyConfigurationName(gradleKotlinDsl())
    compileOnly(restamp.output)
    testImplementation(restamp.output)
    testImplementation(libs.restamp)

    implementation(libs.httpclient)
    implementation(libs.bundles.kotson)
    implementation(libs.coroutines)
    implementation(libs.jgit)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)
    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.feather.core)
    implementation(libs.feather.gson)

    implementation(libs.jbsdiff)

    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }

    testImplementation(libs.mockk)
}
