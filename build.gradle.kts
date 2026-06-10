import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "dev.hivens"
    // Releases pass -PappVersion=<tag> (tag first, then publish -- the
    // libtray flow); anything else is a dev build.
    version = providers.gradleProperty("appVersion").getOrElse("0.1.0-SNAPSHOT")
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
