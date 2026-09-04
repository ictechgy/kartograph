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
            from(rootProject.file("CHANGELOG.md"))
            from(rootProject.file("SECURITY.md"))
            from(rootProject.file("LICENSE"))
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
            )
            from(releaseDocumentation.map { document -> rootProject.file("docs/$document") }) {
                into("docs")
            }
            from(rootProject.file("Skills")) {
                into("Skills")
            }
        }
    }
}
