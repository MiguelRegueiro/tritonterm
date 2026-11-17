plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val archRootfsVersion = providers.gradleProperty("tritonterm.archRootfsVersion")
    .orElse("unconfigured").get()
val archRootfsReleaseBaseUrl = providers.gradleProperty("tritonterm.archRootfsReleaseBaseUrl")
    .orElse("").get()
val archRootfsReleaseTag = providers.gradleProperty("tritonterm.archRootfsReleaseTag")
    .orElse("unconfigured").get()
val androidVersionCode = providers.gradleProperty("tritonterm.androidVersionCode").orElse("202611207").get().toInt()
val androidVersionName = providers.gradleProperty("tritonterm.androidVersionName").orElse("1.0.0").get()
val androidApplicationIdSuffix = providers.gradleProperty("tritonterm.androidApplicationIdSuffix").orElse("").get()
require(androidApplicationIdSuffix.isEmpty() ||
    Regex("\\.[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*").matches(androidApplicationIdSuffix)) {
    "tritonterm.androidApplicationIdSuffix must be empty or a dotted Android application ID suffix"
}
fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.tritonterm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tritonterm.android"
        // Bundled Android PRoot runtime targets Android 9+.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = androidVersionCode
        versionName = androidVersionName
        buildConfigField("String", "ARCH_ROOTFS_VERSION", buildConfigString(archRootfsVersion))
        buildConfigField("String", "ARCH_ROOTFS_RELEASE_BASE_URL", buildConfigString(archRootfsReleaseBaseUrl))
        buildConfigField("String", "ARCH_ROOTFS_RELEASE_TAG", buildConfigString(archRootfsReleaseTag))
    }

    signingConfigs {
        create("tritontermRelease") {
            storeFile = providers.environmentVariable("TRITONTERM_ANDROID_KEYSTORE").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("TRITONTERM_ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("TRITONTERM_ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("TRITONTERM_ANDROID_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("debug") {
            if (androidApplicationIdSuffix.isNotEmpty()) applicationIdSuffix = androidApplicationIdSuffix
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("tritontermRelease")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging {
        dex {
            // Keep installable APK downloads compact; minSdk 28 otherwise stores DEX uncompressed.
            useLegacyPackaging = true
        }
        jniLibs {
            keepDebugSymbols += "**/*.so"
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
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
    implementation("org.bouncycastle:bcpg-jdk18on:1.80.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
