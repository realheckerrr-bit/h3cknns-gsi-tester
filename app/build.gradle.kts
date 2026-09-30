plugins {
    id("com.android.application")
}

android {
    namespace = "com.realheckerrr.gsilab"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.realheckerrr.gsilab"
        minSdk = 23
        targetSdk = 35
        versionCode = 15
        versionName = "0.1.13"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
}
