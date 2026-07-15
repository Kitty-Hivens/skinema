import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Releases pass -PappVersion=<tag> (tag first, then publish -- the
// libtray flow); anything else is a dev build.
val appVersion = providers.gradleProperty("appVersion").getOrElse("0.1.0-SNAPSHOT")

// The natives track the FFmpeg build they carry, not the library API, so a
// library release republishes none of their ~159 MiB of platform bundles
// (ROADMAP M17). Set in gradle.properties; -PnativesVersion overrides.
val nativesVersion = providers.gradleProperty("nativesVersion").get()

allprojects {
    group = "dev.hivens"
    version = if (name == "skinema-natives") nativesVersion else appVersion
}

// CI logs carry only the console; without the message a failed assertion
// is a bare file:line, on every module that ever fails.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
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
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
        configure<SigningExtension> {
            useGpgCmd()
        }
    }
}

// A library release must not re-upload the natives. They sit on their own
// version line, and republishing all 18 unchanged platform bundles with every
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
