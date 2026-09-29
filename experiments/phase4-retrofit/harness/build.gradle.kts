plugins {
    kotlin("jvm") version "2.4.20"
    application
}

// 합성 서비스 소스는 kartograph가 스캔하는 픽스처 그대로 컴파일한다 — 오라클과 스캐너가 같은 소스를 본다.
val clientRoot = rootDir.resolve("../../../fixtures/retrofit-corpus/oracle/retrofit-client/src/main")

kotlin { jvmToolchain(21) }

sourceSets {
    main {
        java.srcDir(clientRoot.resolve("java"))
        kotlin.srcDir(clientRoot.resolve("kotlin"))
    }
}

dependencies {
    // Retrofit 2.12.0은 OkHttp 3.14.9에 묶여 있다. MockWebServer도 같은 OkHttp 버전을 쓴다.
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.okhttp3:mockwebserver:3.14.9")
    // suspend 서비스 메서드를 Retrofit KotlinExtensions가 코루틴으로 기다린다.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

application {
    mainClass.set("dev.kartograph.experiments.retrofit.RetrofitOracleKt")
}
