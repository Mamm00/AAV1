plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    // NOTE: the androidx.benchmark Gradle plugin only supports android
    // library modules with AGP 8.7+ and is NOT required to run
    // macrobenchmarks from a com.android.test module (per the plugin's own
    // diagnostic). Instrumentation comes from benchmark-macro-junit4.
}

android {
    namespace = "com.arena.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Macrobenchmarks run against the benchmarkable build type of :app.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(project(":core:webview"))
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
