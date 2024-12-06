plugins {
    `config-kotlin`
}

repositories {
    gradlePluginPortal()
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

    implementation(libs.restamp)

    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }

    testImplementation(libs.mockk)
}
