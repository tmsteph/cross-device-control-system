plugins {
    id("com.android.application")
}

val buildVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val buildVersionName = System.getenv("VERSION_NAME") ?: "0.2.0-dev"
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")

android {
    namespace = "tech.dvr3.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.dvr3.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = "3dvr-companion"
                // PKCS#12 uses the same password for the private key and keystore.
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
