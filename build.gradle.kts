import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover)
}

// Releases pass -PappVersion=<tag> (tag first, then publish -- the
// libtray flow); anything else is a dev build.
val appVersion = providers.gradleProperty("appVersion").getOrElse("0.1.0-SNAPSHOT")

// The natives track the FFmpeg build they carry, not the library API, so a
// library release republishes none of their ~211 MiB of platform bundles
// (ROADMAP M17). Set in gradle.properties; -PnativesVersion overrides.
val nativesVersion = providers.gradleProperty("nativesVersion").get()

// Present only where the key cannot come from a keyring -- see the signing
// block below.
val inMemoryKey = providers.gradleProperty("signingInMemoryKey")

// The library modules are skinema's own code. skinema-natives is not: it
// ships trimmed FFmpeg builds, LGPL for the core and decode tiers and GPL
// for full, which links x264/x265 under --enable-gpl. A POM cannot scope a
// licence to a classifier, so the natives module declares both and the tier
// table in the README carries the split -- a consumer puts exactly one tier
// on the classpath. The full texts ride inside every bundle.
val ownCodeLicenses = listOf(
    "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0.txt",
)
val ffmpegBundleLicenses = listOf(
    "LGPL-2.1-or-later" to "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt",
    "GPL-2.0-or-later" to "https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt",
)

allprojects {
    group = "dev.hivens"
    version = if (name == "skinema-natives") nativesVersion else appVersion
}

// CI logs carry only the console; without the message a failed assertion
// is a bare file:line, on every module that ever fails.
// Coverage rides every module that carries Kotlin, so the aggregate below can
// pick the ones worth reporting on. What it is FOR is the zero column: a
// hardware-decode path was negotiated away on every open for two months and
// the suite stayed green, because a test that never reaches a line cannot
// fail on it. A percentage is not the deliverable; "no test has ever executed
// this" is.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }

        // The native bundle under test reaches the JVM through the
        // environment, and Gradle sees neither the variable nor the directory
        // it names -- both live outside the project tree. Without declaring
        // them, a test task whose sources are unchanged is served FROM-CACHE,
        // so CI reports on a bundle it never loaded. That matters because the
        // rolling natives release replaces bytes under a fixed path: the
        // bundle changes while the cache key does not.
        val libavDir = providers.environmentVariable("SKINEMA_LIBAV_DIR")
        inputs.property("skinemaLibavDir", libavDir.orElse(""))
        inputs.property("skinemaRequireCaps", providers.environmentVariable("SKINEMA_REQUIRE_CAPS").orElse(""))
        libavDir.map { file(it) }.orNull?.takeIf { it.isDirectory }?.let {
            inputs.dir(it)
                .withPropertyName("skinemaLibavBundle")
                .withPathSensitivity(PathSensitivity.NAME_ONLY)
        }

        // How many tests ran, printed on every run, and a ceiling on how many
        // may skip. Without this a suite that skipped itself into silence and
        // one that passed look identical: a missing fixture CLI, an unreadable
        // bundle or one unparsed line of `ffmpeg -encoders` takes whole suites
        // out and the build stays green. The counts are not observable from a
        // CI log otherwise -- only failures are printed, and Gradle reports
        // totals only when something fails.
        //
        // The ceiling is a backstop against collapse, not a per-environment
        // expectation: the hardware suites skip wherever no GPU is wired up,
        // and a platform with a legitimate gap of its own raises it explicitly
        // rather than everyone loosening to the slackest case.
        val maxSkipped = providers.gradleProperty("maxSkippedTests")
            .orElse(providers.environmentVariable("SKINEMA_MAX_SKIPPED"))
            .orElse("8")
        val xmlDir = reports.junitXml.outputLocation
        val label = path
        doLast {
            var total = 0
            var skipped = 0
            val head = Regex("<testsuite\\b[^>]*")
            fun count(text: String, name: String): Int =
                Regex("\\b" + name + "=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            for (f in xmlDir.get().asFile.listFiles().orEmpty()) {
                if (!f.name.endsWith(".xml")) continue
                val suite = head.find(f.readText()) ?: continue
                total += count(suite.value, "tests")
                skipped += count(suite.value, "skipped")
            }
            logger.lifecycle(label + ": " + (total - skipped) + " of " + total + " tests ran, " + skipped + " skipped")
            val ceiling = maxSkipped.get().toInt()
            if (skipped > ceiling) {
                throw GradleException(
                    label + " skipped " + skipped + " tests, more than the " + ceiling + " allowed -- " +
                        "something the suite needs is missing rather than the suite passing. Raise it " +
                        "deliberately with -PmaxSkippedTests or SKINEMA_MAX_SKIPPED if the gap is real.",
                )
            }
        }
    }
}

// Every warning is an error. A batch of them once piled up unseen behind
// the build cache (which replays a cached compile without re-emitting its
// warnings); as errors they fail the compile, so none is ever cached
// green and `gradlew build` catches the first one in CI.
subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

// Shared Central Portal publishing for every module that opts in by
// applying the vanniktech plugin; modules add only their description.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            // Explicit auto-release: the no-arg form leaves the deployment
            // VALIDATED in the portal, waiting for a manual Publish click.
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()
            coordinates("dev.hivens", project.name, project.version.toString())
            pom {
                name.set(project.name)
                url.set("https://github.com/Kitty-Hivens/skinema")
                licenses {
                    val declared =
                        if (project.name == "skinema-natives") ffmpegBundleLicenses else ownCodeLicenses
                    for ((spdxId, licenseUrl) in declared) {
                        license {
                            name.set(spdxId)
                            url.set(licenseUrl)
                        }
                    }
                }
                developers {
                    developer {
                        id.set("kitty-hivens")
                        name.set("Kitty-Hivens")
                    }
                }
                scm {
                    url.set("https://github.com/Kitty-Hivens/skinema")
                    connection.set("scm:git:https://github.com/Kitty-Hivens/skinema.git")
                }
            }
        }
    }
    plugins.withId("signing") {
        // CI hands the key over as signingInMemoryKey and the publish plugin
        // wires that up itself; a machine with the key in its keyring has no
        // such property and signs through the gpg agent. Setting both would
        // leave whichever ran last in charge.
        if (!inMemoryKey.isPresent) {
            configure<SigningExtension> {
                useGpgCmd()
            }
        }
    }
}

// A library release must not re-upload the natives. They sit on their own
// version line, and republishing all 24 unchanged platform bundles with every
// release is what put the namespace over Maven Central's monthly size limit
// (ROADMAP M17); the natives publish on their own, only when their bundles
// change: `:skinema-natives:publishToMavenCentral -PnativesVersion=<v>`.
tasks.register("publishLibraries") {
    group = "publishing"
    description = "Publish core/skiko/compose to Central Portal: -PappVersion=X.Y.Z"
    dependsOn(
        ":skinema-core:publishToMavenCentral",
        ":skinema-skiko:publishToMavenCentral",
        ":skinema-compose:publishToMavenCentral",
    )
}

// The library modules only. skinema-demo is a harness (its own code is not the
// product) and skinema-natives carries no Kotlin at all.
dependencies {
    kover(project(":skinema-core"))
    kover(project(":skinema-skiko"))
    kover(project(":skinema-compose"))
}

kover {
    reports {
        filters {
            excludes {
                // The FFM binding surface is one declaration per libav symbol,
                // executed only when that symbol is called; counting it as
                // covered code drowns the signal from the logic around it.
                classes("dev.hivens.skinema.libav.LibavAbi*")
            }
        }
    }
}
