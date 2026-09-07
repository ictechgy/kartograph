import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    `java-gradle-plugin`
    kotlin("jvm")
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.0.0"
}

val embedded = configurations.create("embedded") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

configurations.named("compileClasspath") {
    extendsFrom(embedded)
}
configurations.named("runtimeClasspath") {
    extendsFrom(embedded)
}
configurations.named("testRuntimeClasspath") {
    extendsFrom(embedded)
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.7.3")

    embedded(project(":analysis"))
    embedded(project(":core"))
    embedded(project(":export"))
    embedded(project(":index"))

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(project(":index"))
    testRuntimeOnly("com.android.tools.build:gradle-api:8.7.3")
}

gradlePlugin {
    website = "https://github.com/ictechgy/kartograph"
    vcsUrl = "https://github.com/ictechgy/kartograph.git"
    plugins {
        create("kartograph") {
            id = "io.github.ictechgy.kartograph"
            implementationClass = "dev.kartograph.gradle.KartographPlugin"
            displayName = "kartograph"
            description = "Kotlin and Android dependency graph analysis"
            tags = listOf("android", "kotlin", "architecture", "dependency-analysis")
        }
    }
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    archiveBaseName = "kartograph-gradle-plugin"
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from({
        configurations.runtimeClasspath.get().files
            .filter { file -> file.extension == "jar" }
            .sortedBy { file -> file.name }
            .map(::zipTree)
    })
    exclude(
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE*",
        "META-INF/MANIFEST.MF",
        "META-INF/NOTICE*",
        "META-INF/*.RSA",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/versions/**",
        "module-info.class",
    )
    from(rootProject.file("LICENSE")) {
        into("META-INF/kartograph")
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF/kartograph")
    }
    from(rootProject.file("LICENSES")) {
        into("META-INF/licenses")
    }
    manifest {
        attributes(
            "Implementation-Title" to "kartograph Gradle plugin",
            "Implementation-Version" to project.version,
        )
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            // 게시되는 POM이 소비자에게 출처와 라이선스를 알리도록 최소 메타데이터를 채운다.
            pom {
                name.set("kartograph Gradle plugin")
                description.set("Dependency graphs and unreachable-declaration reports for Kotlin and Android builds.")
                url.set("https://github.com/ictechgy/kartograph")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/ictechgy/kartograph/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("ictechgy")
                        url.set("https://github.com/ictechgy")
                    }
                }
                scm {
                    url.set("https://github.com/ictechgy/kartograph")
                    connection.set("scm:git:https://github.com/ictechgy/kartograph.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ictechgy/kartograph.git")
                }
            }
            pom.withXml {
                val dependencyContainers = asNode().get("dependencies") as NodeList
                dependencyContainers.filterIsInstance<Node>().forEach { dependencies ->
                    asNode().remove(dependencies)
                }
            }
        }
    }
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}
