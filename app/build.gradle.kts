plugins {
    id("com.android.application")
}

android {
    namespace = "com.realheckerrr.gsilab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.realheckerrr.gsilab"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
