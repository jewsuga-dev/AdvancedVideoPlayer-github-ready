plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.advancedvideoplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.advancedvideoplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // Release signing comes from environment variables (set by the GitHub workflow from repo
    // secrets). Without them the release build falls back to the debug key so it still installs.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Media3 (ExoPlayer)
    val media3Version = providers.gradleProperty("media3Version").getOrElse("1.9.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")
    implementation("androidx.media3:media3-extractor:$media3Version")

    // Optional deep probe (Dolby Vision / HDR10+ / Atmos / DTS:X detection).
    // The original "com.arthenica:ffmpeg-kit-full:6.0-2" was removed from Maven Central
    // (project retired, April 2025), so it is NOT a hard dependency any more.
    // FfprobeSource loads it by reflection: if you add a compatible FFmpegKit AAR
    // (package com.arthenica.ffmpegkit) it is picked up automatically; if not, metadata
    // still comes from Android's MediaExtractor and from ExoPlayer itself.
    // implementation(files("libs/ffmpeg-kit.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Software DTS / DTS-HD / AC-3 / E-AC-3 / TrueHD decoding that works on every phone (like VLC).
    // The version must match media3Version ("<media3 version>+<build>"). Set in gradle.properties;
    // leave `ffmpegDecoderVersion=` empty or delete the line to build without it.
    providers.gradleProperty("ffmpegDecoderVersion").orNull?.takeIf { it.isNotBlank() }?.let { v ->
        implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$v")
    }

    // Unit tests (pure logic: format detection, stream sniffing, error mapping)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
