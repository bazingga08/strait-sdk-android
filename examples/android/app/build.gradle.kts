import java.util.Properties

plugins {
    id("com.android.application")
}

// Keys come from strait.properties (yours, git-ignored), else the placeholders in strait.example.properties.
val strait = Properties().apply {
    val own = rootProject.file("strait.properties")
    (if (own.exists()) own else rootProject.file("strait.example.properties")).inputStream().use { load(it) }
}

android {
    namespace = "com.straitlink.example"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.straitlink.example"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "STRAIT_PUBLISHABLE_KEY", "\"${strait.getProperty("STRAIT_PUBLISHABLE_KEY")}\"")
        buildConfigField("String", "STRAIT_ENDPOINT", "\"${strait.getProperty("STRAIT_ENDPOINT")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Built from this repo's source (settings.gradle.kts). In your app: the JitPack coordinates
    // with a version, as in the README's Install section.
    implementation("com.github.strait-link:strait-sdk-android")
}
