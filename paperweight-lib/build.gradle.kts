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
        }
    }
}

dependencies {
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
