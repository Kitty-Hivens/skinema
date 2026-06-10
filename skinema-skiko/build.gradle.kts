import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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

// Skiko runtime artifact for the host running the tests; consumers bring
// their own (Compose ships one), so it is test-only here.
val skikoTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macos-$arch"
        else -> "linux-$arch"
    }
}

dependencies {
    // The consumer's Compose provides Skiko at runtime; compiling against
    // the pinned API without shipping a second copy keeps versions single-
    // sourced on their side.
    compileOnly(libs.skiko.awt)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.skiko.awt)
    testRuntimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:${libs.versions.skiko.get()}")
}

tasks.test {
    useJUnitPlatform()
    // Skiko loads its natives via System.load; silence the JDK 25 warning.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
