plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.malachi"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.malachi"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Stable key so in-place auto-updates chain across releases. Committed on purpose
        // (sideloaded beta, no secrets). CI can override via SIGNING_* env if a secret is set.
        create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "../malachi-release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "malachi"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "malachi"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "malachi"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            enableUnitTestCoverage = true
            // The release key on purpose: a debug build must be installable over the release
            // build it is meant to replace on a test device, and Android refuses a signature swap.
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core-filter"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
