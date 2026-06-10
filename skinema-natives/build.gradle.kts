import org.gradle.jvm.tasks.Jar

// Packs the trimmed FFmpeg bundles into per-platform classifier jars
// (ROADMAP.md section 10): resources under dev/hivens/skinema/natives/
// <platform>/, exactly the layout NativeBundle deploys from. Bundles come
// from the rolling natives release; jarLocal packs a locally built one
// for end-to-end checks without the network.

plugins {
    base
}

val resourceRoot = "dev/hivens/skinema/natives"
val nativesTag = "natives-8.1.1"
val platforms = listOf("linux-x64", "windows-x64", "macos-arm64", "macos-x64")

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

platforms.forEach { platform ->
    val archive = layout.buildDirectory.file("bundles/$platform.tar.gz")

    val download = tasks.register("download-$platform") {
        group = "skinema"
        description = "Fetch the $platform bundle from the $nativesTag release"
        outputs.file(archive)
        doLast {
            val url = "https://github.com/Kitty-Hivens/skinema/releases/download/$nativesTag/skinema-natives-$platform.tar.gz"
            val target = archive.get().asFile
            target.parentFile.mkdirs()
            java.net.URI(url).toURL().openStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    tasks.register<Jar>("jar-$platform") {
        group = "skinema"
        description = "Classifier jar for $platform from the natives release"
        dependsOn(download)
        archiveBaseName.set("skinema-natives")
        archiveClassifier.set(platform)
        from(tarTree(resources.gzip(archive))) {
            into("$resourceRoot/$platform")
        }
    }
}

tasks.register("jarAll") {
    group = "skinema"
    description = "Classifier jars for every platform on the natives release"
    dependsOn(platforms.map { "jar-$it" })
}
