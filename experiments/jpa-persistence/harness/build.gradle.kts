plugins {
    kotlin("jvm") version "2.4.20" apply false
}

// 각 하위 프로젝트는 같은 벡터 소스를 서로 다른 Hibernate 버전으로 컴파일·실행한다.
val vectorRoot = rootDir.resolve("../../../fixtures/jpa-naming/src/main")
val oracleRoot = rootDir.resolve("oracle/src/main/java")

/** 하위 프로젝트별 Hibernate·Spring Boot 좌표다 — Boot BOM이 관리하는 버전을 그대로 쓴다. */
val coordinates = mapOf(
    // Spring Boot 3.5.16 BOM의 hibernate.version
    "hibernate6" to listOf("org.hibernate.orm:hibernate-core:6.6.53.Final", "org.springframework.boot:spring-boot:3.5.16"),
    // Spring Boot 4.0.8 BOM의 hibernate.version
    "hibernate7-2" to listOf("org.hibernate.orm:hibernate-core:7.2.24.Final", "org.springframework.boot:spring-boot-hibernate:4.0.8"),
    // Spring Boot 4.1.1 BOM의 hibernate.version
    "hibernate7-4" to listOf("org.hibernate.orm:hibernate-core:7.4.5.Final", "org.springframework.boot:spring-boot-hibernate:4.1.1"),
)

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "application")
    val (hibernate, boot) = coordinates.getValue(name)
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    extensions.configure<SourceSetContainer> {
        named("main") {
            java.srcDir(vectorRoot.resolve("java"))
            java.srcDir(oracleRoot)
            extensions.getByName<SourceDirectorySet>("kotlin").srcDir(vectorRoot.resolve("kotlin"))
        }
    }
    dependencies {
        "implementation"(hibernate)
        // SpringImplicitNamingStrategy 한 클래스만 필요하다 — 전이 의존성은 받지 않는다.
        "implementation"(boot) { isTransitive = false }
    }
    extensions.configure<JavaApplication> {
        mainClass.set("dev.kartograph.experiments.jpanaming.NamingOracle")
    }
}
