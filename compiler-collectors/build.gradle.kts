import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    kotlin("jvm") version "2.4.10"
}

repositories { mavenCentral() }

group = "io.github.ictechgy.kartograph.compiler"
version = file("../VERSION").readText().trim()

configurations.create("kotlinCompilerRuntime")
configurations.create("daggerRuntime")

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    compileOnly("com.google.dagger:dagger-spi:2.59")

    add("kotlinCompilerRuntime", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    add("kotlinCompilerRuntime", "org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    add("kotlinCompilerRuntime", "org.jetbrains:annotations:23.0.0")
    add("daggerRuntime", "com.google.dagger:dagger-compiler:2.59")
    add("daggerRuntime", "com.google.dagger:dagger:2.59")
    add("daggerRuntime", "com.google.dagger:dagger-spi:2.59")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin { jvmToolchain(17) }

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

tasks.withType<Jar>().configureEach {
    archiveFileName.set("kartograph-compiler-collectors.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val integrationTest = tasks.register<Exec>("integrationTest") {
    dependsOn(tasks.named("jar"))
    doFirst {
        val collector = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val kotlinClasspath = configurations.getByName("kotlinCompilerRuntime").files
            .joinToString(File.pathSeparator) { it.absolutePath }
        val daggerClasspath = configurations.getByName("daggerRuntime").files
            .joinToString(File.pathSeparator) { it.absolutePath }
        commandLine(
            "python3",
            layout.projectDirectory.file("tests/run.py").asFile.absolutePath,
            "--collector", collector.absolutePath,
            "--kotlin-classpath", kotlinClasspath,
            "--dagger-classpath", daggerClasspath,
        )
    }
}

tasks.named("check") { dependsOn(integrationTest) }
