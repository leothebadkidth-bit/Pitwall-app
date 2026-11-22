plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pitwall.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pitwall.overlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-OCR"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
