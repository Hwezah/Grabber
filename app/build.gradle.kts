plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hwezah.grabber"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hwezah.grabber"
        minSdk = 26
        targetSdk = 36
        // Each CI build is newer than the last (100 + GitHub's run number), so it installs as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (build != null) 100 + build else 1
        versionName = if (build != null) "0.1.0 (build $build)" else "0.1.0"
    }

    // One APK per ABI (native libraries come later: FFmpeg, yt-dlp, libtorrent). -PuniversalApk adds an all-in-one.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = providers.gradleProperty("universalApk").isPresent
        }
    }

    // A committed debug key (not a secret): every CI build installs over the last, keeping your data.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Not debuggable: Compose runs several times faster, and this is the build you actually use.
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":engine:http"))
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation("junit:junit:4.13.2")
    // org.json is an Android stub on the JVM; the real one for unit tests.
    testImplementation("org.json:json:20240303")
}
