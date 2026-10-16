import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop command-line camera check: ./gradlew :tools:camcheck:installDist
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.rhlshlt.tvrtsp.tools.camcheck.MainKt")
    applicationName = "camcheck"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":core:rtsp"))
}
