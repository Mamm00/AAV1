// Root build file. Plugin versions are declared in gradle/libs.versions.toml
// and applied per-module; nothing is applied to the root project itself.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.benchmark) apply false
    alias(libs.plugins.android.test) apply false
}
