plugins {
    `config-kotlin`
}

dependencies {
    implementation(libs.httpclient)
    implementation(libs.kotson)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)

    // Hypo needs a log4j2 impl
    implementation(libs.log4j.core)

    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.jbsdiff)
}
