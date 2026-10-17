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
        versionCode = 4
        versionName = "1.3"

        // Where the weekly-built toilet & water tiles live. Set in gradle.properties.
        val dataUrl = (project.findProperty("loowater.dataUrl") as String?) ?: ""
        buildConfigField("String", "DATA_BASE_URL", "\"$dataUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Signed with the debug key so a release APK can be sideloaded without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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
