package dev.kartograph.gradle

import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.CompilerCallPositionOptions
import dev.kartograph.index.CompilerEvidenceReader
import dev.kartograph.index.CompilerEvidenceToken
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class CompilerCallPositionMarkerLifecycleTest {
    @Test
    fun `actual javac marker follows true false cache and invalid duplicate lifecycle`(@TempDir root: Path) {
        val collector = collector()
        javaFixture(root, collector)

        val first = build(root, "compileJava", "-PcallPositions=true")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJava")?.outcome)
        val witnessPath = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        val evidencePath = root.resolve("build/kartograph/compiler-evidence/compileJava/references.tsv")
        val trueWitnessText = Files.readString(witnessPath)
        val trueWitness = BuildWitnessCodec.parse(trueWitnessText)
        assertTrue(CompilerEvidenceToken.matches(trueWitness))
        assertEquals(1, trueWitness.inputs.count { it == CompilerCallPositionOptions.enabledInput() })
        assertEquals("3", Files.readString(evidencePath).lineSequence().first().substringAfterLast('\t'))

        val again = build(root, "compileJava", "-PcallPositions=true")
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")?.outcome)
        assertEquals(trueWitnessText, Files.readString(witnessPath))
        build(root, "clean", "-PcallPositions=true")
        assertEquals(TaskOutcome.FROM_CACHE, build(root, "compileJava", "-PcallPositions=true").task(":compileJava")?.outcome)
        assertEquals(trueWitnessText, Files.readString(witnessPath))

        val disabled = build(root, "compileJava", "-PcallPositions=false")
        assertEquals(TaskOutcome.SUCCESS, disabled.task(":compileJava")?.outcome)
        val falseWitness = BuildWitnessCodec.parse(Files.readString(witnessPath))
        assertFalse(CompilerCallPositionOptions.isEnabled(falseWitness.inputs))
        assertTrue(CompilerEvidenceToken.matches(falseWitness))
        assertEquals("1", Files.readString(evidencePath).lineSequence().first().substringAfterLast('\t'))

        val enabledAgain = build(root, "compileJava", "-PcallPositions=true")
        assertEquals(TaskOutcome.SUCCESS, enabledAgain.task(":compileJava")?.outcome)
        assertTrue(CompilerCallPositionOptions.isEnabled(BuildWitnessCodec.parse(Files.readString(witnessPath)).inputs))

        val duplicate = build(root, "compileJava", "-PcallPositions=true", "-PduplicatePlugin=true", fails = true)
        assertContains(duplicate.output, "compiler evidence plugin may be configured only once")
        assertFalse(Files.exists(witnessPath))
    }

    @Test
    fun `actual Kotlin marker follows effective option cache and duplicate rejection`(@TempDir root: Path) {
        val collector = collector()
        val (gradleClasspath, compilerClasspath) = kotlinClasspaths()
        kotlinFixture(root, collector, gradleClasspath, compilerClasspath)

        val first = build(root, "compileKotlin", "-PcallPositions=true", "-PcommaOptions=true", configurationCache = false)
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome)
        val witnessPath = root.resolve("build/kartograph/witnesses/compileKotlin/witness.json")
        val evidencePath = root.resolve("build/kartograph/compiler-evidence/compileKotlin/references.tsv")
        val trueWitnessText = Files.readString(witnessPath)
        val trueWitness = BuildWitnessCodec.parse(trueWitnessText)
        assertTrue(CompilerEvidenceToken.matches(trueWitness))
        assertEquals(1, trueWitness.inputs.count { it == CompilerCallPositionOptions.enabledInput() })
        assertEquals("3", Files.readString(evidencePath).lineSequence().first().substringAfterLast('\t'))
        assertEquals("2.4.10", CompilerEvidenceReader.readEnvelope(evidencePath).evidence.compilerVersion)

        val again = build(root, "compileKotlin", "-PcallPositions=true", "-PcommaOptions=true", configurationCache = false)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileKotlin")?.outcome)
        assertEquals(trueWitnessText, Files.readString(witnessPath))
        build(root, "clean", "-PcallPositions=true", "-PcommaOptions=true", configurationCache = false)
        assertEquals(TaskOutcome.FROM_CACHE, build(root, "compileKotlin", "-PcallPositions=true", "-PcommaOptions=true", configurationCache = false)
            .task(":compileKotlin")?.outcome)
        assertEquals(trueWitnessText, Files.readString(witnessPath))

        assertEquals(TaskOutcome.SUCCESS, build(root, "compileKotlin", "-PcallPositions=false", "-PcommaOptions=true", configurationCache = false)
            .task(":compileKotlin")?.outcome)
        assertFalse(CompilerCallPositionOptions.isEnabled(BuildWitnessCodec.parse(Files.readString(witnessPath)).inputs))
        assertEquals("1", Files.readString(evidencePath).lineSequence().first().substringAfterLast('\t'))

        val duplicate = build(root, "compileKotlin", "-PcallPositions=true", "-PcommaOptions=true", "-PduplicateOption=true",
            fails = true, configurationCache = false)
        assertContains(duplicate.output, "Kotlin call positions option may be configured only once")
        assertFalse(Files.exists(witnessPath))
    }

    @Test
    fun `actual Kotlin marker follows true false true with configuration cache`(@TempDir root: Path) {
        val collector = collector()
        val (gradleClasspath, compilerClasspath) = kotlinClasspaths()
        kotlinFixture(root, collector, gradleClasspath, compilerClasspath)
        val witnessPath = root.resolve("build/kartograph/witnesses/compileKotlin/witness.json")

        assertEquals(TaskOutcome.SUCCESS, build(root, "compileKotlin", "-PcallPositions=true")
            .task(":compileKotlin")?.outcome)
        assertTrue(CompilerCallPositionOptions.isEnabled(BuildWitnessCodec.parse(Files.readString(witnessPath)).inputs))
        val enabledAgain = build(root, "compileKotlin", "-PcallPositions=true")
        assertTrue(enabledAgain.output.contains("Reusing configuration cache"), enabledAgain.output)
        assertEquals(TaskOutcome.UP_TO_DATE, enabledAgain.task(":compileKotlin")?.outcome)

        assertEquals(TaskOutcome.SUCCESS, build(root, "compileKotlin", "-PcallPositions=false")
            .task(":compileKotlin")?.outcome)
        assertFalse(CompilerCallPositionOptions.isEnabled(BuildWitnessCodec.parse(Files.readString(witnessPath)).inputs))
        val disabledAgain = build(root, "compileKotlin", "-PcallPositions=false")
        assertTrue(disabledAgain.output.contains("Reusing configuration cache"), disabledAgain.output)
        assertEquals(TaskOutcome.UP_TO_DATE, disabledAgain.task(":compileKotlin")?.outcome)

        assertTrue(build(root, "compileKotlin", "-PcallPositions=true").task(":compileKotlin")?.outcome in
            setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
        assertTrue(CompilerCallPositionOptions.isEnabled(BuildWitnessCodec.parse(Files.readString(witnessPath)).inputs))
    }

    private fun javaFixture(root: Path, collector: Path) {
        Files.createDirectories(root.resolve("src/main/java/demo"))
        Files.writeString(root.resolve("src/main/java/demo/Caller.java"),
            "package demo; public class Caller { public void use() { Target.hit(); } }")
        Files.writeString(root.resolve("src/main/java/demo/Target.java"),
            "package demo; public class Target { public static void hit() {} }")
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name='marker-java'\nbuildCache { local { directory=file('.fixture-cache') } }")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            def compiler = tasks.named('compileJava', JavaCompile)
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def output = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler).map { it.file('references.tsv') }
            def enabled = providers.gradleProperty('callPositions').getOrElse('true')
            def duplicate = providers.gradleProperty('duplicatePlugin').isPresent()
            compiler.configure {
                options.annotationProcessorPath = files('${groovy(collector)}')
                def plugin = '-Xplugin:KartographEvidence\tcollector=javac-constants' +
                    ' root=${root.toUri()}' + ' output=' + output.get().asFile.toPath().toUri().toASCIIString() +
                    ' token=' + token.get().asFile.toPath().toUri().toASCIIString() + ' callPositions=' + enabled
                options.compilerArgs.add(plugin)
                if (duplicate) options.compilerArgs.add(plugin)
            }
            dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, compiler, 'sample:main',
                files('src/main/java'), files('build.gradle','settings.gradle'), files(), true)
        """.trimIndent())
    }

    private fun kotlinFixture(
        root: Path,
        collector: Path,
        gradleClasspath: List<Path>,
        compilerClasspath: List<Path>,
    ) {
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("src/main/kotlin/demo/Caller.kt"),
            "package demo\nfun use() { Target().hit() }\n")
        Files.writeString(root.resolve("src/main/kotlin/demo/Target.kt"),
            "package demo\nclass Target { fun hit() {} }\n")
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name='marker-kotlin'\nbuildCache { local { directory=file('.fixture-cache') } }")
        Files.writeString(root.resolve("gradle.properties"),
            "kotlin.stdlib.default.dependency=false\nkotlin.compiler.runViaBuildToolsApi=false\n")
        val kotlinRepository = kotlinRepository(root, gradleClasspath)
        val buildscriptClasspath = gradleClasspath.joinToString(", ") { "'${groovy(it)}'" }
        val kotlinCompilerClasspath = compilerClasspath.joinToString(", ") { "'${groovy(it)}'" }
        val kotlinStdlib = compilerClasspath.single { it.fileName.toString() == "kotlin-stdlib-2.4.10.jar" }
        Files.writeString(root.resolve("build.gradle"), """
            buildscript {
                dependencies { classpath files($buildscriptClasspath) }
            }
            plugins { id 'io.github.ictechgy.kartograph' }
            apply plugin: 'org.jetbrains.kotlin.jvm'
            repositories { maven { url = uri('${groovy(kotlinRepository)}') } }
            dependencies { implementation project.files('${groovy(kotlinStdlib)}') }
            configurations.configureEach {
                exclude group: 'org.jetbrains.kotlin', module: 'kotlin-scripting-compiler-embeddable'
            }
            configurations.named('kotlinCompilerClasspath') {
                dependencies.clear()
                dependencies.add(project.dependencies.create(project.files($kotlinCompilerClasspath)))
            }
            configurations.named('kotlinBuildToolsApiClasspath') {
                dependencies.clear()
                dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.10'))
                dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.10'))
                dependencies.add(project.dependencies.create(project.files($kotlinCompilerClasspath)))
            }
            configurations.named('kotlinCompilerPluginClasspathMain') {
                dependencies.clear()
            }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            def compiler = tasks.named('compileKotlin')
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def output = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler).map { it.file('references.tsv') }
            def enabled = providers.gradleProperty('callPositions').getOrElse('true')
            def duplicate = providers.gradleProperty('duplicateOption').isPresent()
            def comma = providers.gradleProperty('commaOptions').isPresent()
            compiler.configure {
                pluginClasspath.from(files('${groovy(collector)}'))
                def pluginOptions = [
                    'plugin:kartograph.compiler-evidence:root=${root}',
                    'plugin:kartograph.compiler-evidence:output=' + output.get().asFile.absolutePath,
                    'plugin:kartograph.compiler-evidence:token=' + token.get().asFile.absolutePath,
                    'plugin:kartograph.compiler-evidence:callPositions=' + enabled]
                if (comma) compilerOptions.freeCompilerArgs.addAll('-P', pluginOptions.join(','))
                else pluginOptions.each { option -> compilerOptions.freeCompilerArgs.addAll('-P', option) }
                if (duplicate) compilerOptions.freeCompilerArgs.addAll(
                    '-P', 'plugin:kartograph.compiler-evidence:callPositions=' + enabled)
            }
            def jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
            dev.kartograph.gradle.KotlinCompilerWitnesses.INSTANCE.kotlinCompile(project, compiler, 'sample:main',
                files('src/main/kotlin'), files('build.gradle','settings.gradle','gradle.properties'), jdk,
                files($kotlinCompilerClasspath), true)
        """.trimIndent())
    }

    private fun kotlinRepository(root: Path, classpath: List<Path>): Path {
        val repository = root.resolve(".kotlin-repository")
        for (artifact in listOf("kotlin-build-tools-compat", "kotlin-build-tools-impl")) {
            val source = classpath.single { it.fileName.toString() == "$artifact-2.4.10.jar" }
            val directory = repository.resolve("org/jetbrains/kotlin/$artifact/2.4.10")
            Files.createDirectories(directory)
            Files.copy(source, directory.resolve("$artifact-2.4.10.jar"))
            Files.writeString(directory.resolve("$artifact-2.4.10.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>$artifact</artifactId>
                  <version>2.4.10</version>
                </project>
            """.trimIndent())
        }
        return repository
    }

    private fun build(
        root: Path,
        vararg args: String,
        fails: Boolean = false,
        configurationCache: Boolean = true,
    ): org.gradle.testkit.runner.BuildResult {
        val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(
            args.toList() + (if (configurationCache) listOf("--configuration-cache") else emptyList()) +
                listOf("--build-cache", "--offline", "--stacktrace"))
        return if (fails) runner.buildAndFail() else runner.build()
    }

    private fun kotlinClasspaths(): Pair<List<Path>, List<Path>> {
        val gradleClasspathValue = System.getenv("KARTOGRAPH_KOTLIN_GRADLE_CLASSPATH")
        assumeTrue(!gradleClasspathValue.isNullOrBlank(), "pinned Kotlin Gradle plugin classpath is required")
        val gradleClasspath = gradleClasspathValue.split(File.pathSeparator).filter(String::isNotBlank)
            .map { Path.of(it).toRealPath() }
        assumeTrue(gradleClasspath.isNotEmpty() && gradleClasspath.all(Files::isRegularFile))
        val compilerClasspathValue = System.getenv("KARTOGRAPH_KOTLIN_COMPILER_CLASSPATH")
        assumeTrue(!compilerClasspathValue.isNullOrBlank(), "pinned Kotlin compiler classpath is required")
        val compilerClasspath = compilerClasspathValue.split(File.pathSeparator).filter(String::isNotBlank)
            .map { Path.of(it).toRealPath() }
        assumeTrue(compilerClasspath.isNotEmpty() && compilerClasspath.all(Files::isRegularFile))
        return gradleClasspath to compilerClasspath
    }

    private fun collector(): Path {
        val value = System.getenv("KARTOGRAPH_COLLECTOR_JAR")
        assumeTrue(!value.isNullOrBlank(), "packaged compiler collector is required")
        return Path.of(value).toRealPath().also { assumeTrue(Files.isRegularFile(it)) }
    }

    private fun groovy(path: Path): String = path.toString().replace("\\", "\\\\").replace("'", "\\'")
}
