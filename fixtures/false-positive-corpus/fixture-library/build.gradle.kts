plugins {
    id("com.android.library")
}

android {
    namespace = "dev.kartograph.fixture.library"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
}
