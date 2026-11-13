plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "app.localbridge"
 compileSdk = 35
 defaultConfig { applicationId = "app.localbridge"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.1.1-alpha" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation(platform("androidx.compose:compose-bom:2025.01.00"))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.material3:material3")
 implementation("org.nanohttpd:nanohttpd:2.3.1")
}
