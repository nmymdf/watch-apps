plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.archiekuo.speedwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.archiekuo.speedwatch"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    // 固定簽章：之後改版可以直接覆蓋安裝
    signingConfigs {
        create("fixed") {
            storeFile = file("keystore.jks")
            storePassword = "speedwatch"
            keyAlias = "sw"
            keyPassword = "speedwatch"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
}
