import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    kotlin("jvm") version "2.4.20"
}

repositories { mavenCentral() }

group = "io.github.ictechgy.kartograph.compiler"
version = file("../VERSION").readText().trim()

configurations.create("kotlinCompilerRuntime")
configurations.create("daggerRuntime")
val kotlinCompilerVersion = "2.4.20"
// 실제 설치물의 runtime pin은 수집기 compiler의 버전과 독립적으로 검증한다.
val distributionStdlibVersion = "2.4.21"

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")
    compileOnly("com.google.dagger:dagger-spi:2.59")
    compileOnly("com.google.devtools.ksp:symbol-processing-api:2.3.12")

    add("kotlinCompilerRuntime", "org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")
    add("kotlinCompilerRuntime", "org.jetbrains.kotlin:kotlin-stdlib:$kotlinCompilerVersion")
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
    from(listOf(file("../LICENSE"), file("../THIRD_PARTY_NOTICES.md"))) { into("META-INF/kartograph") }
    from(file("../LICENSES")) { into("META-INF/licenses") }
    manifest {
        attributes("Implementation-Title" to "kartograph compiler collectors", "Implementation-Version" to project.version)
    }
}

val collectorVersion = version.toString()
tasks.register<Zip>("distZip") {
    dependsOn(tasks.named("jar"))
    archiveBaseName.set("kartograph-compiler-collectors")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into("kartograph-compiler-collectors-$collectorVersion") {
        from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) { into("lib") }
        from("processor_output_witness.py", "processor_output_cache.gradle")
        from("INSTALL.md") {
            rename { "README.md" }
            filter { it.replace("@VERSION@", collectorVersion) }
        }
        from(file("../VERSION"), file("../LICENSE"), file("../THIRD_PARTY_NOTICES.md"))
        from(file("../LICENSES")) { into("LICENSES") }
    }
}

val outputWitnessTest = tasks.register<Exec>("outputWitnessTest") {
    commandLine("python3", "-m", "unittest", "discover", "-s", "tests", "-p", "test_output_witness.py", "-v")
}

val outputAttributionTest = tasks.register<Exec>("outputAttributionTest") {
    dependsOn(tasks.named("jar"))
    dependsOn(outputWitnessTest)
    commandLine("python3", layout.projectDirectory.file("tests/output_attribution.py").asFile.absolutePath)
}

val authoredCallsTest = tasks.register<Exec>("authoredCallsTest") {
    dependsOn(tasks.named("jar"))
    doFirst {
        commandLine("python3", layout.projectDirectory.file("tests/authored_calls.py").asFile.absolutePath,
            "--collector", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
            "--kotlin-classpath", configurations.getByName("kotlinCompilerRuntime").files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}

val callerInventoryTest = tasks.register<Exec>("callerInventoryTest") {
    dependsOn(tasks.named("jar"))
    doFirst {
        commandLine("python3", layout.projectDirectory.file("tests/caller_inventory.py").asFile.absolutePath,
            "--collector", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
            "--compiler-classpath", configurations.getByName("kotlinCompilerRuntime").files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}

val kotlinRuntimeTest = tasks.register<Exec>("kotlinRuntimeTest") {
    commandLine("python3", "-m", "unittest", "discover", "-s", "tests", "-p", "test_kotlin_runtime.py", "-v")
}

val publicAuthoredApiInputTest = tasks.register<Exec>("publicAuthoredApiInputTest") {
    commandLine("python3", "-m", "unittest", "discover", "-s", "tests", "-p", "test_public_authored_api.py", "-v")
}

val publicAuthoredApiTest = tasks.register<Exec>("publicAuthoredApiTest") {
    dependsOn(publicAuthoredApiInputTest)
    doFirst {
        // 소스/class 디렉터리로 대체하지 않는다. 설치물 누락은 verifier가 명시적으로 실패시킨다.
        commandLine("python3", layout.projectDirectory.file("tests/public_authored_api.py").asFile.absolutePath,
            "--distribution", providers.gradleProperty("kartograph.consumerDistribution").orNull.orEmpty(),
            "--version", collectorVersion, "--kotlin-version", kotlinCompilerVersion,
            "--distribution-stdlib-version", distributionStdlibVersion,
            "--compiler-classpath", configurations.getByName("kotlinCompilerRuntime").files.joinToString(File.pathSeparator) { it.absolutePath },
            "--report", layout.buildDirectory.dir("reports/public-authored-api").get().asFile.absolutePath)
    }
}

val integrationTest = tasks.register<Exec>("integrationTest") {
    dependsOn(kotlinRuntimeTest)
    dependsOn(authoredCallsTest, callerInventoryTest, publicAuthoredApiTest)
    dependsOn(outputAttributionTest)
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
            "--kotlin-version", kotlinCompilerVersion,
            "--dagger-classpath", daggerClasspath,
        )
    }
}

tasks.named("check") { dependsOn(integrationTest) }
