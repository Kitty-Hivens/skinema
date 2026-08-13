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

// The bindings are valid for exactly one set of FFmpeg soname majors
// (LibavLibrary), and the natives ride their own version line -- so the two
// coordinates are coupled but a build cannot see it: Gradle resolves them
// independently and a stale natives pin fails at RUNTIME, per platform, down a
// fail-closed path a consumer may never notice. This constraint puts the
// coupling in the published metadata, so bumping the library raises the natives
// with it -- and it holds for the classifier form the natives are consumed as.
//
// The EXACT version, not a range. A range would resolve to whatever revision is
// newest at build time, which is a version no release note paired with this
// library and which changes under an unedited consumer build; it would also
// admit prereleases. This names the one bundle the library was tested against,
// which is exactly what the release notes promise. A consumer who wants another
// (a locally built bundle, a repack under test) overrides with
// `version { strictly("...") }`, which wins over a constraint.
val nativesVersion: String = providers.gradleProperty("nativesVersion").get()

dependencies {
    constraints {
        runtimeOnly("dev.hivens:skinema-natives") {
            version { require(nativesVersion) }
            because(
                "this skinema version binds the soname majors that skinema-natives $nativesVersion carries; " +
                    "a bundle from another FFmpeg line cannot load",
            )
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
