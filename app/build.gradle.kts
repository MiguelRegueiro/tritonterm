plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tritonterm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tritonterm.android"
        // Bundled Android PRoot runtime targets Android 9+.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }
    packaging {
        dex {
            // Keep installable APK downloads compact; minSdk 28 otherwise stores DEX uncompressed.
            useLegacyPackaging = true
        }
        jniLibs {
            // The runtime launches these ELF files by path; APK-backed direct loading is not enough.
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.termux.termux-app:terminal-view:0.118.0") {
        exclude(group = "com.termux.termux-app", module = "terminal-emulator")
    }
    implementation(project(":terminal-emulator"))
    implementation("org.bouncycastle:bcpg-jdk18on:1.80.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
