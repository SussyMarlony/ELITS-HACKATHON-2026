// Top-level build file. Module configuration lives in app/build.gradle.kts.
// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
