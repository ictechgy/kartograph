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

sourceSets.test { resources.srcDir(rootProject.file("fixtures/runtime-corpus")) }

// compiler 코퍼스가 플랫폼별 캐시 경로를 추측하지 않고 실제 검증된 test 의존성을 사용한다.
tasks.register<Copy>("runtimeCorpusDependencies") {
    from(configurations.testRuntimeClasspath.map { configuration ->
        configuration.files.filter { it.name == "javax.inject-1.jar" }
    })
    into(layout.buildDirectory.dir("runtime-corpus"))
}
