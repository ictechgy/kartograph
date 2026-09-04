import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

val requestedJavaVersion = providers.gradleProperty("kartograph.javaVersion")
    .map(String::toInt)
    .getOrElse(17)

allprojects {
    group = "io.github.ictechgy.kartograph"
    version = rootProject.file("VERSION").readText().trim().also { releaseVersion ->
        require(releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "VERSION must contain a stable semantic version"
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlinx.kover")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(requestedJavaVersion)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

dependencies {
    kover(project(":analysis"))
    kover(project(":cli"))
    kover(project(":core"))
    kover(project(":export"))
    kover(project(":gradle-plugin"))
    kover(project(":index"))
    kover(project(":test-support"))
}

kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}
