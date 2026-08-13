import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

kotlin {
    // Build on the JDK the team actually runs; emit bytecode for the floor.
    // The floor is 22: java.lang.foreign went final there and the bindings
    // use nothing newer.
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
    }
}

// Keep the (source-less) Java compile tasks on the same floor, or the
// Kotlin plugin's JVM-target consistency check fails the build.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(22)
}

dependencies {
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

mavenPublishing {
    pom {
        description.set("Video decoding for the JVM: FFmpeg and libwebp through hand-written FFM bindings, paced RGBA frames out.")
    }
}
