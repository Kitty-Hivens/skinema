import org.gradle.api.tasks.testing.logging.TestExceptionFormat
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
    testLogging {
        // CI logs carry only the console; without the message a failed
        // assertion is a bare file:line.
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// M0 decode spike (ROADMAP.md section 11). Linux + system FFmpeg:
//   ./gradlew :skinema-core:spike -Pinput=/path/video.mp4 -Pout=/tmp/spike [-Pframes=N]
val spikeInput = providers.gradleProperty("input")
val spikeOut = providers.gradleProperty("out")
val spikeFrames = providers.gradleProperty("frames")
tasks.register<JavaExec>("spike") {
    group = "skinema"
    description = "M0 decode spike: -Pinput=<video> -Pout=<dir> [-Pframes=N]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.spike.SpikeMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add {
        listOfNotNull(spikeInput.orNull, spikeOut.orNull, spikeFrames.orNull)
    }
}

mavenPublishing {
    pom {
        description.set("Video decoding for the JVM: FFmpeg and libwebp through hand-written FFM bindings, paced RGBA frames out.")
    }
}
