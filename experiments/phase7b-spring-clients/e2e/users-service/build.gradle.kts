plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

kotlin { jvmToolchain(21) }

dependencies {
    // Spring Boot 3.5.16(Spring Framework 6.2.19)이다. 컴파일만 한다 — e2e는 바이트코드 snapshot과 소스를 스캔한다.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
