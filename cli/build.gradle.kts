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
    // 짧은 CLI 실행에서는 C2가 한 번 쓰고 마는 중간 온도 메서드까지 컴파일해 JIT CPU가 분석 CPU를 넘는다.
    // HotSpot 기본 Tier4 진입 임계값만 4배로 올려 C1 단계는 그대로 두고, 실제로 뜨거운 메서드만 C2로 보낸다.
    // GitHub Linux runner 8대(EPYC 7763×5·9V45·9V74·Xeon 8370C) 동일 runner 교차 측정에서 smoke gate 명령
    // wall 중앙값 −8.5%(최소 개선 −6.2%)·CPU −14.7%, 대형 입력(kotlin-compiler-embeddable) metrics −0.4%·
    // cycles −1.3%(최악 +2.4%)이며 출력은 바이트 단위로 같았다. C1 전용(대형 입력 +140%)과 ParallelGC(대형 +14.5%)는
    // 기각했다. 사용자 JAVA_OPTS/KARTOGRAPH_OPTS는 이 기본값 뒤에 붙으므로 같은 flag로 되돌릴 수 있다.
    applicationDefaultJvmArgs = listOf(
        "-XX:Tier4InvocationThreshold=20000",
        "-XX:Tier4MinInvocationThreshold=2400",
        "-XX:Tier4CompileThreshold=60000",
        "-XX:Tier4BackEdgeThreshold=160000",
    )
}

sourceSets.main {
    resources.srcDir(rootProject.file("Skills"))
}

val distributionVersion = project.version.toString()
val pluginInstallationPin = Regex("""(id\("io\.github\.ictechgy\.kartograph"\) version ")[^"]+(")""")

// 인라인 문서 renderer가 바뀌면 이전 Copy 결과를 재사용하지 않는다.
for (taskName in listOf("distZip", "distTar", "installDist")) {
    tasks.named(taskName) {
        inputs.file(project.file("build.gradle.kts"))
    }
}

distributions {
    main {
        contents {
            // 공개 README는 발행본을 고정하고 archive 문서는 동봉 버전을 가리킨다.
            for (readme in listOf("README.md", "README.ko.md")) {
                from(rootProject.file(readme)) {
                    filteringCharset = "UTF-8"
                    filter { line: String ->
                        when {
                            line.startsWith("The install example pins") ->
                                "This archive's install example targets bundled version $distributionVersion. Portal publication is separate; use the bundled CLI or build matching modules until the plugin version is available."
                            line.startsWith("설치 예제는") ->
                                "이 archive의 설치 예제는 동봉 버전 $distributionVersion 을 가리킨다. Portal 발행은 별도이며 공개 전에는 동봉 CLI나 같은 소스의 모듈을 사용한다."
                            else -> pluginInstallationPin.replace(line) { match ->
                                "${match.groupValues[1]}$distributionVersion${match.groupValues[2]}"
                            }
                        }
                    }
                }
            }
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
                "GIT-IMPACT-VALIDATION.md",
                "FEEDBACK-026.md",
                "ANDROID-CLI-INPUTS.md",
                "ANDROID-CALLBACK-INPUTS.md",
                "DATA-BINDING-EVIDENCE.md",
                "ANDROID-RESOURCE-EVIDENCE.md",
                "ANDROID-RESOURCE-CAPTURE.md",
                "ANDROID-RESOURCE-PRODUCER-EVIDENCE.md",
                "ANDROID-RESOURCE-BINDINGS.md",
                "ANDROID-RESOURCE-IMPACT.md",
                "ANDROID-RESOURCE-IMPACT-EXPERIMENT.md",
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
