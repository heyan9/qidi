plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.qidi.magnifier"
    compileSdk = 34
    defaultConfig { applicationId = "com.qidi.magnifier"; minSdk = 26; targetSdk = 33; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { getByName("debug") { storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
}
