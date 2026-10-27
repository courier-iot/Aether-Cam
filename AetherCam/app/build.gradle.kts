plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.steampunk.cam"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.steampunk.cam"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.0")
    val camx = "1.3.4"
    implementation("androidx.camera:camera-core:$camx")
    implementation("androidx.camera:camera-camera2:$camx")
    implementation("androidx.camera:camera-lifecycle:$camx")
    implementation("androidx.camera:camera-view:$camx")
}
