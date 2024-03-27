plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "paperweight"

include("paperweight-core", "paperweight-lib", "paperweight-patcher", "paperweight-userdev")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
