import org.gradle.jvm.tasks.Jar

// Packs the trimmed FFmpeg bundles into per-platform classifier jars
// (ROADMAP.md section 10): resources under dev/hivens/skinema/natives/
// <platform>/, exactly the layout NativeBundle deploys from. Bundles come
// from the rolling natives release; jarLocal packs a locally built one
// for end-to-end checks without the network.

plugins {
    // java-library gives the publication an (empty) main jar plus sources/
    // javadoc stubs Central requires; the real payload is the classifier
    // jars attached below.
    `java-library`
    alias(libs.plugins.maven.publish)
}

mavenPublishing {
    pom {
        description.set("Trimmed FFmpeg runtimes for skinema in modular tiers (core/decode/full), one classifier jar per tier and platform.")
    }
}

val resourceRoot = "dev/hivens/skinema/natives"
val nativesTag = "natives-8.1.1"
val platforms = listOf("linux-x64", "linux-arm64", "windows-x64", "macos-arm64", "macos-x64")
// The modular tiers (ROADMAP.md section 4). Each (tier, platform) ships as
// its own classifier jar "<tier>-<platform>"; a consumer puts exactly one
// tier per platform on the runtime classpath. The unpacked layout is keyed
// by platform only, so the loader stays tier-agnostic -- it loads whatever
// bundle the platform carries.
val tiers = listOf("core", "decode", "full")

// ./gradlew :skinema-natives:jarLocal -Pplatform=linux-x64 -PbundleDir=<dir>
val localPlatform = providers.gradleProperty("platform")
val localBundle = providers.gradleProperty("bundleDir")
tasks.register<Jar>("jarLocal") {
    group = "skinema"
    description = "Pack a locally built bundle: -Pplatform=<p> -PbundleDir=<dir>"
    archiveBaseName.set("skinema-natives")
    archiveClassifier.set(localPlatform.getOrElse("local"))
    from(localBundle) {
        into("$resourceRoot/${localPlatform.getOrElse("local")}")
    }
}

tiers.forEach { tier ->
    platforms.forEach { platform ->
        val archive = layout.buildDirectory.file("bundles/$tier-$platform.tar.gz")

        val download = tasks.register("download-$tier-$platform") {
            group = "skinema"
            description = "Fetch the $tier $platform bundle from the $nativesTag release"
            outputs.file(archive)
            doLast {
                val url = "https://github.com/Kitty-Hivens/skinema/releases/download/$nativesTag/skinema-natives-$tier-$platform.tar.gz"
                val target = archive.get().asFile
                target.parentFile.mkdirs()
                uri(url).toURL().openStream().use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }

        tasks.register<Jar>("jar-$tier-$platform") {
            group = "skinema"
            description = "Classifier jar for the $tier $platform bundle from the natives release"
            dependsOn(download)
            archiveBaseName.set("skinema-natives")
            archiveClassifier.set("$tier-$platform")
            from(tarTree(resources.gzip(archive))) {
                into("$resourceRoot/$platform")
            }
        }
    }
}

tasks.register("jarAll") {
    group = "skinema"
    description = "Classifier jars for every tier and platform on the natives release"
    dependsOn(tiers.flatMap { tier -> platforms.map { "jar-$tier-$it" } })
}

// Every tier x platform bundle rides the same publication as a classifier.
afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        tiers.forEach { tier ->
            platforms.forEach { platform ->
                artifact(tasks.named("jar-$tier-$platform"))
            }
        }
    }
}
