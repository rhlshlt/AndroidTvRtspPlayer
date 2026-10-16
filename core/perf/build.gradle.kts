// Device profile (decoders, tiers), process sampling and CSV metrics for Phase 0.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.rhlshlt.tvrtsp.core.perf"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:rtsp"))
    implementation(libs.androidx.annotation)
}
