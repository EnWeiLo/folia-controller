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

    testOptions {
        // org.json and android.* calls in unit tests return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("lyricFixtures", rootProject.file("tests/lyrics").absolutePath)
            it.systemProperty("liveReport", rootProject.file("app/build/reports/live-lyrics.txt").absolutePath)
            it.environment("LIVE_LYRICS", System.getenv("LIVE_LYRICS") ?: "")
            it.testLogging { showStandardStreams = true; events("passed", "failed") }
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
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
