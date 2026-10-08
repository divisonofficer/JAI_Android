plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cgjnkim.mobile_jai.tapo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// No SDK: KLAP is HTTP, SHA-256 and AES-CBC, all in the platform.
dependencies {
    testImplementation(libs.junit)
}
