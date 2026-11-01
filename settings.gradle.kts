// foojay-resolver-convention -- safety net for the jvmToolchain(25) call in
// skinema-core. If the machine has no matching JDK, Gradle downloads one from
// the foojay.io distributions API instead of failing with a resolver error.
// 1.0.0 dropped the reference to JvmVendorSpec.IBM_SEMERU that 0.10.0 carried;
// that field is gone in Gradle 9, so the older resolver crashed the moment it
// was actually invoked (a host without the toolchain JDK present).
pluginManagement {
    repositories {
        // The Android Gradle Plugin is published here and nowhere else; the
        // filter keeps every other plugin resolving from the portal.
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.(android|testing)(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
include(":skinema-natives")
// The Android demo needs an Android SDK to configure; a run that opts out
// of the Android target with -Pskinema.android=false leaves it out too.
if (providers.gradleProperty("skinema.android").orNull != "false") include(":skinema-demo-android")
