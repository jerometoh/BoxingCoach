plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jerome.boxingcoach"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jerome.boxingcoach"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
    // Embedded offline neural TTS (Piper/VITS models via ONNX Runtime) — no phone-side
    // setup needed. App falls back to system TTS automatically if no model is bundled
    // or this fails to load. See README "Embedded voice" section.
    // NOTE: coordinates are repo-name-based (per k2-fsa/sherpa-onnx's own jitpack.yml,
    // which just re-publishes their prebuilt .aar release asset via JitPack) —
    // NOT "sherpa-onnx-android", which doesn't exist as a JitPack artifact.
    implementation("com.github.k2-fsa:sherpa-onnx:v1.12.40")
}
