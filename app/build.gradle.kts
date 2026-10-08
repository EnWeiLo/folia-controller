plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "tw.folia.controller"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.folia.controller"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber
        versionName = "1.0.$ciRunNumber"
    }

    // A keystore committed to the repo, so every CI build is signed with the same key and
    // a new APK installs over the old one instead of failing with a signature mismatch.
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.keystore")
            storePassword = "foliacontroller"
            keyAlias = "folia"
            keyPassword = "foliacontroller"
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

    androidResources {
        // Vite emits chunk files whose names can start with "_" or ".", keep every asset.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
