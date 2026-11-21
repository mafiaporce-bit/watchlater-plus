plugins {
    id("com.android.application")
}

android {
    namespace = "com.jpsums.watchlater"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jpsums.watchlater"
        minSdk = 26
        targetSdk = 34
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = run + 1
        versionName = "1.$run"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed key from the repo so every new build installs over the old one
            storeFile = file("watchlater-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // serves the app page from inside the APK
    implementation("androidx.webkit:webkit:1.11.0")
}
