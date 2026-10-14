plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cgjnkim.mobile_jai.relay"
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

// No SDK: the module speaks a few plain HTTP GETs.
dependencies {
    testImplementation(libs.junit)
}
