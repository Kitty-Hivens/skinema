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
        // Must travel through the Compose DSL: the plugin assigns the run
        // task's jvmArgs from here in afterEvaluate, clobbering anything
        // appended at configuration time.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// Demo runner: ./gradlew :skinema-demo:run -Pvideo=/path/file.mp4 [-Psound]
val demoVideo = providers.gradleProperty("video")
val demoSound = providers.gradleProperty("sound")
val demoSubs = providers.gradleProperty("subs")

// Read-ahead depth for every demo task: -PreadAhead=N (default 1).
val demoReadAhead = providers.gradleProperty("readAhead")
tasks.withType<JavaExec>().configureEach {
    demoReadAhead.orNull?.let { systemProperty("skinema.demo.readAhead", it) }
}
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        argumentProviders.add {
            listOfNotNull(
                demoVideo.orNull,
                if (demoSound.isPresent) "sound" else null,
                demoSubs.orNull?.let { "subs=$it" },
            )
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

// Windowed background harness (several players, unmount/remount, fallback):
//   ./gradlew :skinema-demo:harness -Pvideo=/path/file.mp4 [-Pplayers=N]
val harnessPlayers = providers.gradleProperty("players")
val harnessChurn = providers.gradleProperty("churn")
tasks.register<JavaExec>("harness") {
    group = "skinema"
    description = "Windowed consumer-shaped harness: -Pvideo=<file> [-Pplayers=N] [-Pchurn=seconds] [-PreadAhead=N]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.demo.HarnessMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add {
        listOfNotNull(demoVideo.orNull, harnessPlayers.orNull, harnessChurn.orNull)
    }
}

// Headless seek diagnostic:
//   SKINEMA_DEBUG_SEEK=1 ./gradlew :skinema-demo:seekbench -Pvideo=/path/file.mp4
tasks.register<JavaExec>("seekbench") {
    group = "skinema"
    description = "Scripted seeks against a real file with landing-cost output: -Pvideo=<file>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.demo.SeekBenchMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add { listOfNotNull(demoVideo.orNull) }
}

// Headless soak for the adoption bar:
//   ./gradlew :skinema-demo:soak -Pvideo=/path/file.mp4 [-Pminutes=N]
val soakMinutes = providers.gradleProperty("minutes")
tasks.register<JavaExec>("soak") {
    group = "skinema"
    description = "Long looping decode run with RSS reporting: -Pvideo=<file> [-Pminutes=N] [-PreadAhead=N]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.demo.SoakMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add {
        listOfNotNull(demoVideo.orNull, soakMinutes.orNull)
    }
}
