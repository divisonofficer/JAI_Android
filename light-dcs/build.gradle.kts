plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cgjnkim.mobile_jai.dcs"
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

// No SDK: the controller speaks semicolon-terminated ASCII over plain TCP.
dependencies {
    testImplementation(libs.junit)
}
