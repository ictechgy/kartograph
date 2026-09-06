plugins {
    id("com.android.application")
    id("io.github.ictechgy.kartograph")
    id("com.google.devtools.ksp")
}

kartograph {
    keepRules.from(
        "proguard-rules.pro",
        "rules/included.pro",
        "../fixture-library/consumer-rules.pro",
    )
    strict.set(providers.gradleProperty("kartograph.strict").map(String::toBoolean).orElse(false))
    includePrivateMembers.set(providers.gradleProperty("kartograph.includePrivateMembers").map(String::toBoolean).orElse(false))
    reportFormat.set(providers.gradleProperty("kartograph.reportFormat").orElse("text"))
    includeSourcePaths.set(providers.gradleProperty("kartograph.includeSourcePaths").map(String::toBoolean).orElse(false))
}

android {
    namespace = "dev.kartograph.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kartograph.fixture"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("javax.inject:javax.inject:1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0")
    implementation("androidx.room:room-runtime:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation(project(":fixture-library"))
}
