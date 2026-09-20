plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.gradle.licenser)
    implementation(libs.gradle.spotless)
    implementation(libs.gradle.shadow)
    implementation(libs.gradle.kotlin.dsl)
    implementation(kotlin("gradle-plugin", embeddedKotlinVersion))
    implementation(libs.gradle.plugin.publish)
}
