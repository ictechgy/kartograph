import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    kotlin("jvm")
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.1.1"
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
configurations.named("testCompileClasspath") {
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
    // 콜백 사실 동등성 테스트가 실제 CLI를 별도 JVM으로 실행한다. 배포 JAR(embedded)에는 들어가지 않는다.
    testRuntimeOnly(project(":cli"))
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
            // configuration cache 재사용은 Android fixture 게이트로 검증한다.
            compatibility {
                features {
                    configurationCache = true
                }
            }
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

val testKitNativeDirectory = layout.buildDirectory.dir("testkit-native")
val expectedTestKitNativeDirectory = layout.projectDirectory.asFile.canonicalFile.resolve("build/testkit-native")
// wrapper 변경이 고정 native fixture의 소비 환경을 바꾸지 않도록 분리한다.
val safeArgsCurrentGradleVersion = "9.7.1"
val safeArgsCurrentGradleDirectory = layout.buildDirectory.dir("safeargs-current-gradle")
val prepareSafeArgsCurrentGradle = if (gradle.gradleVersion == safeArgsCurrentGradleVersion) null else
    tasks.register<Exec>("prepareSafeArgsCurrentGradle") {
        val script = rootProject.file("Scripts/prepare-safeargs-current-gradle.py")
        inputs.files(script, rootProject.file("Scripts/observe-safeargs-gradle813.py"))
        inputs.property("version", safeArgsCurrentGradleVersion)
        inputs.property("distributionSha256", "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a")
        outputs.dir(safeArgsCurrentGradleDirectory)
        // 네트워크 준비 출력은 원격 build cache에서 복원하지 않는다.
        outputs.cacheIf { false }
        commandLine("python3", script.absolutePath, "--output", safeArgsCurrentGradleDirectory.get().dir("prepared").asFile.absolutePath)
    }
val safeArgsCurrentGradleHome = if (gradle.gradleVersion == safeArgsCurrentGradleVersion)
    requireNotNull(gradle.gradleHomeDir) else
    safeArgsCurrentGradleDirectory.get().dir("prepared/distribution/gradle-$safeArgsCurrentGradleVersion").asFile
// worker 수명인 native DLL은 실패한 테스트 뒤에도 worker 종료 후 정리한다.
val cleanTestKitNativeLibraries = tasks.register<Delete>("cleanTestKitNativeLibraries") {
    delete(testKitNativeDirectory)
    isFollowSymlinks = false
    doFirst {
        check(testKitNativeDirectory.get().asFile.canonicalFile == expectedTestKitNativeDirectory) {
            "Refusing to clean a native-library directory outside the isolated test output"
        }
    }
}

// Android 소비 검증은 AGP와 같은 buildscript classloader에서 실제 배포 JAR를 적용한다.
tasks.test {
    prepareSafeArgsCurrentGradle?.let { dependsOn(it) }
    inputs.dir(safeArgsCurrentGradleHome).withPropertyName("safeArgsCurrentGradleDistribution")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("kartograph.test.safeargsCurrentGradleHome", safeArgsCurrentGradleHome.absolutePath)
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(pluginJar)
    inputs.file(rootProject.file(".kartograph-self.pro")).withPropertyName("selfAnalysisKeepRules")
    systemProperty("kartograph.pluginJar", pluginJar.get().asFile.absolutePath)
    // TestKit 임시 홈의 daemon만 종료할 때 현재 테스트와 같은 Gradle 설치를 사용한다.
    systemProperty("kartograph.test.gradleHome", requireNotNull(gradle.gradleHomeDir).absolutePath)
    // Tooling API client가 로드한 DLL은 테스트 worker가 끝날 때 해제되므로 메서드 TempDir에 두지 않는다.
    systemProperty("org.gradle.native.dir", testKitNativeDirectory.get().asFile.absolutePath)
    finalizedBy(cleanTestKitNativeLibraries)
    // 명시적인 in-process 통합 테스트는 Gradle도 같은 JVM에서 실행하므로 여유 heap을 준다.
    maxHeapSize = "2g"
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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

apply(from = rootProject.file("gradle/runtime-sbom.gradle"))
