plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cgjnkim.mobile_jai.helios"
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

// The Helios is another GigE Vision device, so it rides on :camera-jai's GVCP, GVSP and
// GenICam code; only what is particular to a ToF camera lives here.
dependencies {
    api(project(":camera-jai"))
    testImplementation(libs.junit)
}
