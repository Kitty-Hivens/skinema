import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
}

// The same opt-out as skinema-core: the Android target needs an SDK to
// configure, and the desktop runs in CI build without one.
// The device tests take skinema-core's fixtures task, which exists only once
// that project is configured, and alphabetical order configures this one first.
if (providers.gradleProperty("skinema.android").map { it != "false" }.getOrElse(true)) evaluationDependsOn(":skinema-core")

val androidTarget = providers.gradleProperty("skinema.android").map { it != "false" }.getOrElse(true)
if (androidTarget) apply(plugin = "com.android.kotlin.multiplatform.library")

// Skiko runtime artifact for the host running the tests, resolved the way
// skinema-skiko resolves it.
val skikoTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macos-$arch"
        else -> "linux-$arch"
    }
}

kotlin {
    jvmToolchain(25)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
    }
    if (androidTarget) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "dev.hivens.skinema.compose"
            compileSdk = 37
            minSdk = 29
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
            withDeviceTest {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // The surface and the helpers both platforms share; only the frame and
        // overlay holders differ, Skia images on the desktop and hardware
        // bitmaps on Android.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":skinema-core"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
            }
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        jvmMain.dependencies {
            implementation(project(":skinema-skiko"))
        }
        if (androidTarget) {
            getByName("androidMain").dependsOn(jvmSharedMain)
            // skinema-core's fixtures, packaged into this test APK too.
            getByName("androidDeviceTest").resources.srcDir(project(":skinema-core").tasks.named("androidFixtures"))
            getByName("androidDeviceTest").dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.junit)
            }
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(22)
}

dependencies {
    // The headless scene renderer draws through Skia, so the tests need
    // skiko's native for the host -- the same test-only arrangement
    // skinema-skiko already makes, rather than dragging the whole desktop
    // bundle in for one renderer. Consumers bring their own through Compose.
    add("jvmTestRuntimeOnly", "org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:${libs.versions.skiko.get()}")
}

// The module carries no Compose resources, and the plugin's copy into Android
// assets is left without an output directory for a library's device test,
// which fails the build over nothing to copy.
tasks.matching { it.name.endsWith("ComposeResourcesToAndroidAssets") }.configureEach { enabled = false }

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

mavenPublishing {
    pom {
        description.set("Compose VideoSurface and player-state helpers for skinema, on the desktop and on Android.")
    }
}
