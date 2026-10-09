plugins {
    application
    kotlin("jvm")
}

dependencies {
    implementation(project(":analysis"))
    implementation(project(":core"))
    implementation(project(":export"))
    implementation(project(":index"))

    testImplementation(kotlin("test"))
    testImplementation("javax.inject:javax.inject:1")
}

application {
    applicationName = "kartograph"
    mainClass = "dev.kartograph.cli.MainKt"
}

sourceSets.main {
    resources.srcDir(rootProject.file("Skills"))
}

distributions {
    main {
        contents {
            from(rootProject.file("README.md"))
            from(rootProject.file("README.ko.md"))
            from(rootProject.file("VERSION"))
            from(rootProject.file("CHANGELOG.md"))
            from(rootProject.file("SECURITY.md"))
            from(rootProject.file("LICENSE"))
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("LICENSES")) {
                into("LICENSES")
            }
            val releaseDocumentation = listOf(
                "GRAPH-EXPORT.md",
                "DEPENDENCIES.md",
                "PROCESSOR-GENERATION.md",
                "DECISION-truth-source.md",
                "LIMITATIONS.md",
                "PHASE2-VALIDATION.md",
                "PHASE3-ADOPTION.md",
                "PHASE4-AGENT.md",
                "PHASE5-VALIDATION.md",
                "PLAN.md",
                "PRD.md",
                "RESEARCH.md",
                "PR-CHECK.md",
                "PUBLIC-VALIDATION.md",
                "IMPACT-PLAN.md",
                "IMPACT.md",
                "BUILD-PROVENANCE.md",
                "COMPILER-EVIDENCE.md",
                "INDEX-CACHE.md",
                "MCP.md",
                "SPRING-ROUTES.md",
                "SPRING-CLIENTS.md",
            )
            from(releaseDocumentation.map { document -> rootProject.file("docs/$document") }) {
                into("docs")
            }
            from(rootProject.file("Skills")) {
                into("Skills")
            }
            from(rootProject.file("Scripts/check-pr.py")) {
                into("Scripts")
            }
            from(rootProject.file("Scripts/check-impact.py")) { into("Scripts") }
        }
    }
}

apply(from = rootProject.file("gradle/runtime-sbom.gradle"))

sourceSets.test {
    resources.srcDir(rootProject.file("fixtures/runtime-corpus"))
    // isthmus 공유 적합성 벡터와 lock 파일이다. 테스트가 sha256을 대조한 뒤 생산자 케이스를 실행한다.
    resources.srcDir(rootProject.file("fixtures/isthmus-conformance"))
    // JPA 명명 벡터(엔티티 소스와 Hibernate 실행 결과)다. 테스트가 소스를 임시 프로젝트로 복사해 스캔한다.
    resources.srcDir(rootProject.file("fixtures/jpa-naming"))
    // Retrofit 합성 서비스 소스와 MockWebServer가 기록한 요청이다. 테스트가 소스를 임시 프로젝트로 복사해 routes로 스캔한다.
    resources.srcDir(rootProject.file("fixtures/retrofit-corpus/oracle"))
    // Spring 합성 클라이언트 앱 소스·설정과 로컬 프록시가 기록한 요청이다. 테스트가 소스를 임시 프로젝트로 복사해 routes로 스캔한다.
    resources.srcDir(rootProject.file("fixtures/spring-clients-corpus/oracle"))
}

// compiler 코퍼스가 플랫폼별 캐시 경로를 추측하지 않고 실제 검증된 test 의존성을 사용한다.
tasks.register<Copy>("runtimeCorpusDependencies") {
    from(configurations.testRuntimeClasspath.map { configuration ->
        configuration.files.filter { it.name == "javax.inject-1.jar" }
    })
    into(layout.buildDirectory.dir("runtime-corpus"))
}
