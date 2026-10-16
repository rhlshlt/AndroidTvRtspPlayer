// The two engines Phase 0 compares, behind one StreamEngine interface.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.rhlshlt.tvrtsp.core.player"
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
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.rtsp.client.android)
    implementation(libs.kotlinx.coroutines.android)
}
