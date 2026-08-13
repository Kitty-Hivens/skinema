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

// The bindings are valid for exactly one FFmpeg soname major (LibavAbi), and
// the natives ride their own version line -- so the two coordinates are
// coupled but a build cannot see it: Gradle resolves them independently and
// a stale natives pin fails at RUNTIME, per platform, down a fail-closed path
// a consumer may never notice. This constraint makes the coupling explicit,
// so bumping only the library raises the natives with it. Published into the
// module metadata, it survives into the consumer's resolution (and holds for
// the classifier form the natives are actually consumed as).
val nativesLine = providers.gradleProperty("nativesVersion").get().substringBefore('.')

dependencies {
    constraints {
        runtimeOnly("dev.hivens:skinema-natives") {
            version { require("[$nativesLine.0,${nativesLine.toInt() + 1}.0)") }
            because("this version binds the FFmpeg $nativesLine soname majors; a bundle from another line cannot load")
        }
    }
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
