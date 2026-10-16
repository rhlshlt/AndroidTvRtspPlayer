// AGP 9 has Kotlin built in for Android modules, so org.jetbrains.kotlin.android is not applied.
// Declaring the Kotlin plugins here puts AGP and KGP in the same class loader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
