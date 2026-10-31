import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

// The Android target needs an Android SDK to configure at all, and several
// places that build this project have none: the Alpine container the musl
// rows test in, the natives runners, the arm runner images. They pass
// -Pskinema.android=false and test the desktop alone. Opting out by name
// rather than by noticing a missing SDK, so a machine that was meant to
// build Android and cannot says so instead of quietly building less.
val androidTarget = providers.gradleProperty("skinema.android").map { it != "false" }.getOrElse(true)
if (androidTarget) apply(plugin = "com.android.kotlin.multiplatform.library")

/**
 * The Android suite's media, generated on the host by the ffmpeg CLI as the
 * desktop suite generates its own, and packaged into the test APK as
 * resources. Generated rather than committed for the reason the desktop
 * fixtures are: a test asserts on meaning (frame count, pts grid, a colour
 * within tolerance), and a file built from a stated recipe says what it
 * means. A host without the CLI fails here by name rather than skipping.
 */
abstract class AndroidFixtures : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("fixtures").apply { mkdirs() }
        fun ffmpeg(name: String, vararg args: String) {
            exec.exec {
                commandLine(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args + dir.resolve(name).path)
            }
        }
        val red = arrayOf("-f", "lavfi", "-i", "color=c=red:s=320x240:r=30:d=1")
        ffmpeg("red-h264.mp4", *red, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "10", "-bf", "0")
        ffmpeg(
            "green-vp9.webm", "-f", "lavfi", "-i", "color=c=0x00FF00:s=320x240:r=15:d=1",
            "-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-g", "5",
        )
        // AV1, which this decade's phones mostly decode in software alone: the
        // hardware policy's REQUIRE case.
        ffmpeg(
            "blue-av1.mp4", "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=15:d=1",
            "-c:v", "libaom-av1", "-cpu-used", "8", "-pix_fmt", "yuv420p",
        )
        // As a live muxer writes it: no duration in the header, so a reader
        // learns how long it is only by reaching the end.
        ffmpeg(
            "green-live.webm", "-f", "lavfi", "-i", "color=c=0x00FF00:s=320x240:r=15:d=1",
            "-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-g", "5", "-f", "webm", "-live", "1",
        )
        // HD converted with the BT.709 matrix, once tagged and once not: the
        // untagged file is the geometry rule's case.
        val blueHd = arrayOf(
            "-f", "lavfi", "-i", "color=c=0x2050C0:s=1280x720:r=30:d=0.2",
            "-vf", "scale=out_color_matrix=bt709:out_range=tv", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        )
        ffmpeg("blue-hd-tagged.mp4", *blueHd, "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709")
        ffmpeg("blue-hd-untagged.mp4", *blueHd)
        ffmpeg("sine-aac.m4a", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=1", "-c:a", "aac")
        ffmpeg(
            "tone-flac24.flac", "-f", "lavfi", "-i", "sine=f=440:sample_rate=44100:d=1",
            "-ac", "2", "-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24",
        )
        ffmpeg(
            "red-with-sound.mp4", *red, "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
        )
        ffmpeg(
            "long-with-sound.mp4", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=30:d=4",
            "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=4",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "30", "-bf", "0", "-c:a", "aac", "-shortest",
        )
        // Red on the left, blue on the right, then tagged with a display
        // rotation, so which way a frame arrives turned is readable off it.
        ffmpeg(
            "split.mp4", "-f", "lavfi", "-i", "color=c=red:s=160x240:r=30:d=1",
            "-f", "lavfi", "-i", "color=c=blue:s=160x240:r=30:d=1",
            "-filter_complex", "hstack", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        )
        ffmpeg("split-rot90.mp4", "-display_rotation", "90", "-i", dir.resolve("split.mp4").path, "-c", "copy")
        // Two cues with a gap between them, so a clear is observable as well
        // as a cue: side-loaded as it is, and muxed into the two containers
        // that carry text tracks most often.
        val srt = dir.resolve("subs.srt")
        srt.writeText("1\n00:00:00,000 --> 00:00:01,500\nHello\n\n2\n00:00:02,000 --> 00:00:04,000\nWorld\n\n")
        val long = dir.resolve("long-with-sound.mp4").path
        ffmpeg("subs.mkv", "-i", long, "-i", srt.path, "-map", "0", "-map", "1", "-c", "copy", "-c:s", "srt", "-metadata:s:s:0", "language=eng")
        // The three animated image formats, ten frames at 10 fps each, for the
        // Android frame source.
        val anim = arrayOf("-f", "lavfi", "-i", "testsrc2=s=160x120:r=10:d=1")
        ffmpeg("anim.gif", *anim, "-loop", "0")
        ffmpeg("anim.apng", *anim, "-plays", "0", "-f", "apng")
        ffmpeg("anim.webp", *anim, "-c:v", "libwebp_anim", "-loop", "0", "-lossless", "1")
        // Red, green, blue, a tenth of a second each: what a frame source has
        // to get right (order, timing, colour) readable off three frames.
        val rgb = arrayOf(
            "-f", "lavfi", "-i", "color=c=red:s=64x48:r=10:d=0.1",
            "-f", "lavfi", "-i", "color=c=lime:s=64x48:r=10:d=0.1",
            "-f", "lavfi", "-i", "color=c=blue:s=64x48:r=10:d=0.1",
            "-filter_complex", "[0][1][2]concat=n=3:v=1",
        )
        // A palette built from the frames, since GIF's default one has two bits
        // of blue and turns pure blue into 170; and passthrough, or the
        // constant-rate logic drops the concat's last frame and the file holds two.
        ffmpeg(
            "rgb.gif", *rgb.dropLast(2).toTypedArray(),
            "-filter_complex", "[0][1][2]concat=n=3:v=1,split[a][b];[a]palettegen[p];[b][p]paletteuse",
            "-loop", "0", "-fps_mode", "passthrough",
        )
        ffmpeg("rgb.apng", *rgb, "-plays", "0", "-fps_mode", "passthrough", "-f", "apng")
        ffmpeg("rgb.webp", *rgb, "-c:v", "libwebp_anim", "-loop", "0", "-lossless", "1", "-fps_mode", "passthrough")
        // The tenth frame of each testsrc2 animation as ffmpeg composes it, raw
        // RGBA: the reference the Android composer is held to, on files whose
        // frames depend on the ones before them.
        for (format in listOf("gif", "apng", "webp")) {
            ffmpeg(
                "anim-$format-last.rgba", "-i", dir.resolve("anim.$format").path,
                "-vf", "select=eq(n\\,9)", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgba",
            )
        }
        ffmpeg("still.jpg", "-f", "lavfi", "-i", "color=c=red:s=64x48", "-frames:v", "1")
        ffmpeg("still.png", "-f", "lavfi", "-i", "color=c=blue:s=64x48", "-frames:v", "1")
        // Red over blue, for which way up a drawn picture lands, and red at half
        // opacity, for whether its alpha comes back straight.
        ffmpeg(
            "split-v.png", "-f", "lavfi", "-i", "color=c=red:s=64x24", "-f", "lavfi", "-i", "color=c=blue:s=64x24",
            "-filter_complex", "vstack", "-frames:v", "1",
        )
        ffmpeg("half-red.png", "-f", "lavfi", "-i", "color=c=red@0.5:s=64x48,format=rgba", "-frames:v", "1")
        ffmpeg("subs-movtext.mp4", "-i", long, "-i", srt.path, "-map", "0", "-map", "1", "-c", "copy", "-c:s", "mov_text")
        // Two sound tracks of different shapes, stereo at 48 kHz and mono at
        // 44.1 kHz, so a switch between them reopens a sink while it plays.
        ffmpeg(
            "two-sounds.mp4", "-f", "lavfi", "-i", "testsrc2=s=160x120:r=30:d=4",
            "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=4", "-f", "lavfi", "-i", "sine=f=880:sample_rate=44100:d=4",
            "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "30",
            "-c:a", "aac", "-ac:a:0", "2", "-ac:a:1", "1",
        )
        // Two titled chapters over a second of picture, in each container that
        // carries them, read back against what was written. The .m4a is sound
        // alone, for the audio decoder.
        val meta = dir.resolve("chapters.txt")
        meta.writeText(
            ";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=500\ntitle=Intro\n" +
                "[CHAPTER]\nTIMEBASE=1/1000\nSTART=500\nEND=1000\ntitle=Drop\n",
        )
        for (ext in listOf("mp4", "mov", "mkv", "webm")) {
            val codec = if (ext == "webm") arrayOf("-c:v", "libvpx-vp9") else arrayOf("-c:v", "libx264", "-pix_fmt", "yuv420p")
            ffmpeg(
                "chapters.$ext", "-f", "lavfi", "-i", "color=c=red:s=64x48:r=10:d=1", "-i", meta.path,
                "-map", "0:v", "-map_chapters", "1", *codec,
            )
        }
        ffmpeg("chapters.m4a", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=1", "-i", meta.path, "-map", "0:a", "-map_chapters", "1", "-c:a", "aac")
        // Nero's list alone, and the QuickTime track alone, from the mp4 with
        // the other one renamed out of the way: a reader that finds either one
        // has to be reading that one.
        val both = dir.resolve("chapters.mp4").readBytes()
        dir.resolve("chapters-nero.mp4").writeBytes(renamed(both, "tref", "free"))
        dir.resolve("chapters-track.mp4").writeBytes(renamed(both, "chpl", "free"))
    }

    /** [bytes] with the first box named [from] renamed to [to], size unchanged: a box a reader then skips. */
    private fun renamed(bytes: ByteArray, from: String, to: String): ByteArray {
        val name = from.toByteArray(Charsets.ISO_8859_1)
        val at = (4 until bytes.size - 4).first { i -> (0 until 4).all { bytes[i + it] == name[it] } }
        return bytes.copyOf().also { to.toByteArray(Charsets.ISO_8859_1).copyInto(it, at) }
    }
}

val androidFixtures = tasks.register<AndroidFixtures>("androidFixtures") {
    outputDir.set(layout.buildDirectory.dir("generated/androidFixtures"))
}

kotlin {
    // Build on the JDK the team actually runs; emit bytecode for the floor.
    // The floor is 22: java.lang.foreign went final there and the bindings
    // use nothing newer.
    jvmToolchain(25)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
    }

    if (androidTarget) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "dev.hivens.skinema"
            compileSdk = 37
            // ImageReader with usage flags, Bitmap.wrapHardwareBuffer and the
            // platform Opus encoder all arrive at 29, and nothing needs more.
            minSdk = 29
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
            // The decoders are MediaCodec, which only a device or an emulator
            // has, so the Android suite runs there rather than on the host.
            withDeviceTest {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }

    compilerOptions {
        // VideoPlayer, VideoDecoder, AudioDecoder and MediaWriter are expect
        // classes, each actualized by its platform's engine (ROADMAP M23).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // The API every platform shares. Only JVM-family targets ever depend
        // on it, so it keeps JDK types such as Path and AutoCloseable rather
        // than retreating to what common Kotlin offers.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        if (androidTarget) {
            getByName("androidMain") {
                dependsOn(jvmSharedMain)
                dependencies {
                    implementation(libs.media3.exoplayer)
                }
            }
            getByName("androidDeviceTest") {
                resources.srcDir(androidFixtures)
                dependencies {
                    implementation(libs.kotlin.test.junit)
                    implementation(libs.androidx.test.runner)
                    implementation(libs.androidx.test.junit)
                }
            }
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
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
// repack under test) declares their own `version { strictly("...") }`, which
// wins: a strict version in the consuming build outranks one arriving through
// a published module's constraint. `resolutionStrategy.force` works too. The
// runtime still refuses a mismatched soname major either way, so overriding
// buys a different bundle, not a different set of majors.
//
// On the jvm target's runtime only. The natives are the desktop engine, and a
// target that does not load them must not carry a constraint naming them.
val nativesVersion: String = providers.gradleProperty("nativesVersion").get()

dependencies {
    constraints {
        add("jvmMainRuntimeOnly", "dev.hivens:skinema-natives") {
            version { strictly(nativesVersion) }
            because(
                "this skinema version binds the soname majors that skinema-natives $nativesVersion carries; " +
                    "a bundle from another FFmpeg line cannot load",
            )
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

mavenPublishing {
    pom {
        description.set("Video decoding for the JVM: FFmpeg through hand-written FFM bindings, paced RGBA frames out.")
    }
}
