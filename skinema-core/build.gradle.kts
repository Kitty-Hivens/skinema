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
// `strictly`, not `require`. Both pin the version a consumer gets by default,
// but `require` is a floor: it blocks only the downgrade half, so a consumer
// naming a bundle from a LATER FFmpeg line silently keeps it, and that is the
// direction the breakage actually comes from -- the library asks for the
// soname majors of its own line and finds none of them. `strictly` turns that
// into a resolution conflict at build time instead of a load failure at
// runtime. A range would be worse still: it resolves to whatever revision is
// newest at build time, a version no release note paired with this library,
// changing under an unedited consumer build, and it admits prereleases.
//
// A consumer who deliberately wants another bundle (a locally built one, a
// repack under test) overrides with
// `resolutionStrategy { force("dev.hivens:skinema-natives:...") }`, which wins
// over a strict constraint. Declaring their own `strictly` does not -- two
// disagreeing strict versions are a conflict, by design.
val nativesVersion: String = providers.gradleProperty("nativesVersion").get()

dependencies {
    constraints {
        runtimeOnly("dev.hivens:skinema-natives") {
            version { strictly(nativesVersion) }
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
