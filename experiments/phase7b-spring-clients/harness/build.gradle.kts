plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    application
}

// 합성 클라이언트 앱 소스는 kartograph가 스캔하는 픽스처 그대로 컴파일한다 — 오라클과 스캐너가 같은 소스·설정을 본다.
val clientRoot = rootDir.resolve("../../../fixtures/spring-clients-corpus/oracle/spring-client/src/main")

kotlin {
    jvmToolchain(21)
    compilerOptions { javaParameters.set(true) }
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

sourceSets {
    main {
        java.srcDir(clientRoot.resolve("java"))
        kotlin.srcDir(clientRoot.resolve("kotlin"))
        resources.srcDir(clientRoot.resolve("resources"))
    }
}

dependencies {
    // Spring Boot 3.5.16은 Spring Framework 6.2.19를 관리한다(결합 규칙을 확인한 소스와 같은 버전).
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-web")
    // WebClient만 쓴다 — reactor-netty 없이 JDK HttpClient 커넥터가 선택된다.
    implementation("org.springframework:spring-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

application {
    mainClass.set("dev.kartograph.experiments.springclient.SpringClientOracleKt")
}
