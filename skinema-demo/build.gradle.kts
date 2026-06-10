import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
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
    implementation(project(":skinema-compose"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "dev.hivens.skinema.demo.MainKt"
    }
}

// Demo runner: ./gradlew :skinema-demo:run -Pvideo=/path/file.mp4
val demoVideo = providers.gradleProperty("video")
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        argumentProviders.add {
            listOfNotNull(demoVideo.orNull)
        }
    }
}

// Extra jar(s) on manual runs -- e.g. a skinema-natives classifier jar to
// exercise the bundled-natives path end to end:
//   -PextraClasspath=/path/skinema-natives-...-linux-x64.jar
// doFirst, not configuration: the tasks assign `classpath =` in their own
// config blocks, and configure-action ordering would let that overwrite
// the addition.
val extraClasspath = providers.gradleProperty("extraClasspath")
tasks.withType<JavaExec>().configureEach {
    doFirst {
        extraClasspath.orNull?.let { classpath += files(it) }
    }
}

// Headless soak for the adoption bar:
//   ./gradlew :skinema-demo:soak -Pvideo=/path/file.mp4 [-Pminutes=N]
val soakMinutes = providers.gradleProperty("minutes")
tasks.register<JavaExec>("soak") {
    group = "skinema"
    description = "Long looping decode run with RSS reporting: -Pvideo=<file> [-Pminutes=N]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.demo.SoakMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add {
        listOfNotNull(demoVideo.orNull, soakMinutes.orNull)
    }
}
