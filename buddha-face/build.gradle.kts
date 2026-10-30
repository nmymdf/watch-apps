plugins {
    id("com.android.application")
}

// 佛像錶面（Watch Face Format，沒有程式碼，只有圖片與 watchface.xml）
android {
    namespace = "com.archiekuo.buddhaface"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.archiekuo.buddhaface"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.5"
    }

    // 固定簽章：之後改版可以直接覆蓋安裝，不用先移除
    signingConfigs {
        create("fixed") {
            storeFile = file("keystore.jks")
            storePassword = "buddhaface"
            keyAlias = "bf"
            keyPassword = "buddhaface"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
