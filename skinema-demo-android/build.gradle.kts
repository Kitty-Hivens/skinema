import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

// A harness for looking at the Android player on a device, not a product:
// ./gradlew :skinema-demo-android:installDebug, then push videos into
// /sdcard/Android/data/dev.hivens.skinema.demo/files/.
android {
    namespace = "dev.hivens.skinema.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.hivens.skinema.demo"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "demo"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":skinema-compose"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
}
