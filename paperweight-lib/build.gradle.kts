plugins {
    `config-kotlin`
}

dependencies {
    implementation(libs.httpclient)
    implementation(libs.kotson)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)
    implementation(libs.slf4j.jdk14) // slf4j impl for hypo
    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.jbsdiff)

    implementation("net.minecraftforge:DiffPatch:2.0.8-local:all") {
        isTransitive = false
    }
}
