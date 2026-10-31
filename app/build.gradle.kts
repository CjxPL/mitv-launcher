plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Jeden stały klucz podpisu: każda nowa wersja instaluje się na poprzednią.
val releaseKeystore = System.getenv("LAUNCHER_KEYSTORE_PATH")

android {
    namespace = "pl.fouroptima.mitvlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.fouroptima.mitvlauncher"
        minSdk = 28 // Mi TV Stick HD: Android 9
        targetSdk = 35
        versionCode = (System.getenv("LAUNCHER_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.${System.getenv("LAUNCHER_VERSION_CODE") ?: "0"}"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LAUNCHER_KEYSTORE_PASSWORD")
                keyAlias = "launcher"
                keyPassword = System.getenv("LAUNCHER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }
}

dependencies {
    // Celowo bez Compose: stick ma 1 GB RAM, klasyczne widoki startują szybciej i lepiej znoszą pilota.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
