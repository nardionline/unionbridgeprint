plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "al.baumarket.unionbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "al.baumarket.unionbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
