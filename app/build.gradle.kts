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

    // Native libraries compressed in the APK: smaller download.
    packaging { jniLibs { useLegacyPackaging = true } }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":engine:http"))
    implementation("androidx.documentfile:documentfile:1.1.0")
    // Dark pages in the browser (algorithmic darkening).
    implementation("androidx.webkit:webkit:1.12.1")
    // Pull-to-refresh in the Browser.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // The in-app VPN: WireGuard's own Android tunnel library (GoBackend), with a config from Mullvad, Proton or similar.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // Video from YouTube and ~1,800 sites (yt-dlp, Unlicense; wrapper GPL-3.0), and FFmpeg for audio conversion.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // aria2c: a multi-connection downloader yt-dlp hands video files to (GPL-2.0).
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
    // Torrents: libtorrent (BSD) through libtorrent4j (MIT), the version WatchHub ships.
    val lt = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$lt")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation("junit:junit:4.13.2")
    // org.json is an Android stub on the JVM; the real one for unit tests.
    testImplementation("org.json:json:20240303")
}
