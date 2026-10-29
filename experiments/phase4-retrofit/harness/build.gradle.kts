plugins {
    kotlin("jvm") version "2.4.20"
    application
}

// 합성 서비스 소스는 kartograph가 스캔하는 픽스처 그대로 컴파일한다 — 오라클과 스캐너가 같은 소스를 본다.
val clientRoot = rootDir.resolve("../../../fixtures/retrofit-corpus/oracle/retrofit-client/src/main")
// 인터셉터 결합 코퍼스는 스캐너가 따로 스캔하는 별도 프로젝트지만 오라클은 한 빌드로 컴파일한다(패키지가 다르다).
val interceptorRoot = rootDir.resolve("../../../fixtures/retrofit-corpus/oracle/interceptor-client/src/main")

kotlin { jvmToolchain(21) }

sourceSets {
    main {
        java.srcDir(clientRoot.resolve("java"))
        kotlin.srcDir(clientRoot.resolve("kotlin"))
        java.srcDir(interceptorRoot.resolve("java"))
        kotlin.srcDir(interceptorRoot.resolve("kotlin"))
    }
}

dependencies {
    // Retrofit 2.12.0은 OkHttp 3.14.9에 묶여 있다. MockWebServer도 같은 OkHttp 버전을 쓴다.
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.okhttp3:mockwebserver:3.14.9")
    // suspend 서비스 메서드를 Retrofit KotlinExtensions가 코루틴으로 기다린다.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // 인터셉터 결합 코퍼스의 DI 모양이다. Dagger는 어노테이션만 쓰고(생성기 없음) 오라클이 제공 함수를 직접 부른다.
    implementation("com.google.dagger:dagger:2.59")
    implementation("io.insert-koin:koin-core-jvm:4.2.2")
}

application {
    mainClass.set("dev.kartograph.experiments.retrofit.RetrofitOracleKt")
}
