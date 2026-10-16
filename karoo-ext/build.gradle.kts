// Vendored copy of https://github.com/hammerheadnav/karoo-ext v1.1.9 (lib module),
// Apache License 2.0, Copyright SRAM LLC. Build file trimmed of docs/publishing.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.hammerhead.karooext"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        buildConfigField("String", "LIB_VERSION", "\"1.1.9\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
}
