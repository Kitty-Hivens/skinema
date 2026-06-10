// foojay-resolver-convention -- safety net for the jvmToolchain(25) call in
// skinema-core. If the machine has no matching JDK, Gradle downloads one from
// the foojay.io distributions API instead of failing with a resolver error.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform resolution needs google() even off Android
        // (androidx annotation/collection artifacts live there).
        google()
    }
}

rootProject.name = "skinema"

include(":skinema-core")
include(":skinema-skiko")
include(":skinema-compose")
include(":skinema-demo")
