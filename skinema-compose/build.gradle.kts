import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
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
    api(project(":skinema-core"))
    implementation(project(":skinema-skiko"))
    implementation(compose.runtime)
    implementation(compose.foundation)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    pom {
        description.set("Compose Desktop VideoSurface and player-state helpers for skinema.")
    }
}
