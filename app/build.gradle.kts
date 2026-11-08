plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.dan.loowater"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.dan.loowater"
        minSdk = 23
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"

        // Where the weekly-built toilet & water tiles live. Set in gradle.properties.
        val dataUrl = (project.findProperty("loowater.dataUrl") as String?) ?: ""
        buildConfigField("String", "DATA_BASE_URL", "\"$dataUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One shared key in the repo, so an APK built on any computer (or by Claude) can
    // update the copy already on the Karoo. Android refuses updates signed differently.
    // The key itself is not in the repo (it's public); without it, builds fall back to
    // this computer's debug key and can't update a copy signed with the shared key.
    val sharedKey = rootProject.file("keystore/loowater.jks")
    signingConfigs {
        if (sharedKey.exists()) {
            create("shared") {
                storeFile = sharedKey
                storePassword = "loowater"
                keyAlias = "loowater"
                keyPassword = "loowater"
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = signing
        }
        release {
            signingConfig = signing
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":karoo-ext"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
