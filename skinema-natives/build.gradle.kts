import org.gradle.jvm.tasks.Jar
import java.security.MessageDigest

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

// These jars hold shared objects and no classes at all, but java-library still
// stamps the building JDK into the module metadata as a minimum. That made
// every one of the 24 classifier jars unresolvable for a consumer below JDK 25
// -- with an error telling them to pick an earlier version, of which none
// exists -- while the library itself asks only for 22, which is what the
// README promises. Say 22 here so the natives never raise the floor.
java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

mavenPublishing {
    pom {
        description.set(
            "Trimmed FFmpeg runtimes for skinema in modular tiers, one classifier jar per tier and platform. " +
                "The core and decode tiers are LGPL; the full tier adds x264/x265 software encode and is GPL. " +
                "Each bundle carries its own licence texts.",
        )
    }
}

val resourceRoot = "dev/hivens/skinema/natives"

// The rolling release the bundles come from. The module's version is
// <ffmpeg>-<revision> (gradle.properties), so the tag is its FFmpeg half and
// the two cannot drift apart.
val nativesTag = "natives-" + version.toString().substringBefore('-')
// linux-musl-* are their own platforms, not a variant of linux-*: a glibc
// shared object cannot load into a musl process at all, so Alpine and
// Void-musl need bundles built against musl or nothing works (#33).
val platforms = listOf(
    "linux-x64", "linux-arm64", "linux-musl-x64", "linux-musl-arm64",
    "windows-x64", "windows-arm64", "macos-arm64", "macos-x64",
)
// The modular tiers (ROADMAP.md section 4). Each (tier, platform) ships as
// its own classifier jar "<tier>-<platform>"; a consumer puts exactly one
// tier per platform on the runtime classpath. The unpacked layout is keyed
// by platform only, so the loader stays tier-agnostic -- it loads whatever
// bundle the platform carries.
val tiers = listOf("core", "decode", "full")

// What each bundle is expected to be. See bundle-checksums.txt for why.
val checksumFile = layout.projectDirectory.file("bundle-checksums.txt")
val expectedChecksums: Map<String, String> by lazy {
    val f = checksumFile.asFile
    if (!f.isFile) {
        emptyMap()
    } else {
        f.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                parts[1].trim() to parts[0].trim()
            }
    }
}

fun sha256Of(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

/**
 * Re-reads every bundle's sha256 from the release and rewrites
 * bundle-checksums.txt. Run it after a deliberate natives rebuild, then
 * review the diff: a line that moved is a bundle that changed.
 */
tasks.register("refreshBundleChecksums") {
    group = "skinema"
    description = "Rewrite bundle-checksums.txt from the $nativesTag release"
    doLast {
        val lines = mutableListOf<String>()
        for (tier in tiers) {
            for (platform in platforms) {
                val url =
                    "https://github.com/Kitty-Hivens/skinema/releases/download/$nativesTag/skinema-natives-$tier-$platform.tar.gz"
                val tmp = File.createTempFile("skinema-bundle", ".tar.gz")
                try {
                    uri(url).toURL().openStream().use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    lines += "${sha256Of(tmp)}  $tier-$platform"
                } finally {
                    tmp.delete()
                }
            }
        }
        val f = checksumFile.asFile
        val header = f.readLines().takeWhile { it.startsWith("#") || it.isBlank() }
        f.writeText((header + lines.sortedBy { it.substringAfter("  ") }).joinToString("\n") + "\n")
        logger.lifecycle("bundle-checksums.txt: ${lines.size} bundles")
    }
}

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
            // The expectation is an INPUT, or the check below is skippable by
            // the thing it guards against. A task with an output and no inputs
            // is up to date whenever its output is still there, so a warm
            // build/bundles from an earlier revision was packed without the
            // verification ever running -- and the tag is rolling, so "still
            // there" says nothing about which bytes they are. Measured on a
            // tree carrying 22 bundles from a previous rebuild: one bundle
            // re-downloaded and was checked, the other 23 were packed unread.
            inputs.property("expectedSha256", providers.provider { expectedChecksums["$tier-$platform"] ?: "" })
            doLast {
                val url = "https://github.com/Kitty-Hivens/skinema/releases/download/$nativesTag/skinema-natives-$tier-$platform.tar.gz"
                val target = archive.get().asFile
                target.parentFile.mkdirs()
                uri(url).toURL().openStream().use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                // The tag is rolling and the module's version does not move
                // when its contents do, so what this URL serves is not a
                // function of what was reviewed. Packed unverified, a jar
                // bound for Central could carry a bundle no gate ever ran
                // against.
                val expected = expectedChecksums["$tier-$platform"]
                    ?: throw GradleException(
                        "no checksum for $tier-$platform in bundle-checksums.txt -- " +
                            "run :skinema-natives:refreshBundleChecksums and review the diff",
                    )
                val actual = sha256Of(target)
                if (actual != expected) {
                    throw GradleException(
                        "the $tier $platform bundle is not the one this revision was built against.\n" +
                            "  expected: $expected\n" +
                            "  got:      $actual\n" +
                            "If the natives were deliberately rebuilt, run " +
                            ":skinema-natives:refreshBundleChecksums and review the diff.",
                    )
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
