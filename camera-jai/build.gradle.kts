plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cgjnkim.mobile_jai.jai"
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
        // android.util.Log is a stub on the JVM; let it return quietly in unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

// No native code. GigE Vision is plain UDP -- GVCP for control, GVSP for pixels -- so
// the whole stack is DatagramSockets bound to the tethered Ethernet interface.
dependencies {
    testImplementation(libs.junit)
}
