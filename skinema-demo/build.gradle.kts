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
    // Declared rather than taken transitively, because skinema-compose keeps
    // skiko off its consumers' compile classpath on purpose: a Compose
    // consumer needs VideoSurface and not the image holders. The soak uses
    // VideoFrameImage directly, which is exactly the shape of a consumer that
    // renders through Skia without Compose -- so it depends on it the same way
    // that consumer would.
    implementation(project(":skinema-skiko"))
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
// Sound and GPU decode for the soak. A run that leaves both off measures the
// plain looping path and nothing else -- which is not where the threads, the
// device handles or the downloaded frames live.
val demoSoakAudio = providers.gradleProperty("soakAudio")
val demoSoakHardware = providers.gradleProperty("hardware")
// Frames through a real VideoFrameImage, on the two threads a consumer uses.
// Without it the soak stops at the mailbox and never builds a Skia image --
// which left the one component whose job IS native memory outside the run
// that exists to prove native memory does not grow.
val demoSoakImages = providers.gradleProperty("soakImages")
// Linear 0..1 gain for the soak's sound run. Zero keeps every part of the
// audio path and removes only what comes out of the speakers.
val demoSoakVolume = providers.gradleProperty("soakVolume")
// Seconds between report lines. A minute suits a two-hour run; a short
// diagnostic one needs the shape, which three lines cannot show.
val demoSoakReport = providers.gradleProperty("soakReport")
tasks.withType<JavaExec>().configureEach {
    demoReadAhead.orNull?.let { systemProperty("skinema.demo.readAhead", it) }
    demoSoakAudio.orNull?.let { systemProperty("skinema.demo.soakAudio", it) }
    demoSoakHardware.orNull?.let { systemProperty("skinema.demo.hardware", it) }
    demoSoakImages.orNull?.let { systemProperty("skinema.demo.soakImages", it) }
    demoSoakVolume.orNull?.let { systemProperty("skinema.demo.soakVolume", it) }
    demoSoakReport.orNull?.let { systemProperty("skinema.demo.soakReport", it) }
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

// M0 decode spike (ROADMAP.md section 11) -- the raw VideoDecoder, no player:
//   ./gradlew :skinema-demo:spike -Pinput=/path/video.mp4 -Pout=/tmp/spike [-Pframes=N]
val spikeInput = providers.gradleProperty("input")
val spikeOut = providers.gradleProperty("out")
val spikeFrames = providers.gradleProperty("frames")
tasks.register<JavaExec>("spike") {
    group = "skinema"
    description = "M0 decode spike: -Pinput=<video> -Pout=<dir> [-Pframes=N]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.demo.SpikeMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add {
        listOfNotNull(spikeInput.orNull, spikeOut.orNull, spikeFrames.orNull)
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
// A heap cap for the soak, and the reason it is worth a knob: the question the
// adoption bar asks is whether RSS grows, and an unconstrained run answers it
// badly. Left to a default heap, one collection happened in a whole hour --
// so the series showed heap climbing and RSS following it, which looks like a
// leak and is only allocation outpacing a collector that had no reason to run.
// Capping the heap makes collections frequent, and a floor that repeats across
// many of them is the actual evidence that nothing accumulates.
val soakHeap = providers.gradleProperty("heap")
tasks.register<JavaExec>("soak") {
    group = "skinema"
    description = "Long looping decode run with RSS reporting: -Pvideo=<file> [-Pminutes=N] [-PreadAhead=N] [-PsoakAudio=true] [-Phardware=AUTO] [-Pheap=256m] [-PsoakImages=true] [-PsoakVolume=0] [-PsoakReport=10]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.hivens.skinema.demo.SoakMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    soakHeap.orNull?.let { jvmArgs("-Xmx$it") }
    argumentProviders.add {
        listOfNotNull(demoVideo.orNull, soakMinutes.orNull)
    }
}
