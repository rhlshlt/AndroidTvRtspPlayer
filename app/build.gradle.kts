// Phase 0 spike app: plays the C320WS with both engines, measures delay, decoder capacity and load.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.rhlshlt.tvrtsp"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rhlshlt.tvrtsp"
        minSdk = 30
        // 37 would turn on Android 17's local-network permission prompt; see docs/DECISIONS.md.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"
        // The adb camera hook (debug.AdbCameraReceiver) is declared in the main manifest but
        // enabled only in the debug and perf builds below.
        manifestPlaceholders["adbHookEnabled"] = "false"
    }

    signingConfigs {
        // A shared, public test key (app/signing/README.md), so builds from any GitHub Actions run
        // or PC install over each other; each runner would otherwise make its own debug key.
        // Replace it with a private key before Phase 1 saves camera passwords (DECISIONS.md P0-20).
        getByName("debug") {
            storeFile = file("signing/phase0-test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["adbHookEnabled"] = "true"
        }
        release {
            isMinifyEnabled = false // R8 and a private signing key come in Phase 7
        }
        // Non-debuggable build for CPU and latency measurements, signed with the shared test key
        // and with the adb test hook. Select it in Build > Select Build Variant.
        create("perf") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            manifestPlaceholders["adbHookEnabled"] = "true"
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
}

dependencies {
    implementation(project(":core:rtsp"))
    implementation(project(":core:player"))
    implementation(project(":core:perf"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
