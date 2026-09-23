plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // ✅ ADD THIS LINE — applies the Compose compiler plugin
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.lockscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.lockscreen"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ✅ Older BOM compatible with AGP 8.5.1 + compileSdk 34
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
}