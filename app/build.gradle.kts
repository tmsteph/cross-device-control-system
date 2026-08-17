plugins {
    id("com.android.application")
}

android {
    namespace = "tech.dvr3.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.dvr3.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
