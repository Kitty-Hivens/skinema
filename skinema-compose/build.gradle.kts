import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
}

// Skiko runtime artifact for the host running the tests, resolved the way
// skinema-skiko resolves it.
val skikoTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macos-$arch"
        else -> "linux-$arch"
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(22)
}

dependencies {
    api(project(":skinema-core"))
    implementation(project(":skinema-skiko"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)

    testImplementation(libs.kotlin.test)
    // The headless scene renderer draws through Skia, so the tests need
    // skiko's native for the host -- the same test-only arrangement
    // skinema-skiko already makes, rather than dragging the whole desktop
    // bundle in for one renderer. Consumers bring their own through Compose.
    testRuntimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:${libs.versions.skiko.get()}")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    pom {
        description.set("Compose Desktop VideoSurface and player-state helpers for skinema.")
    }
}
