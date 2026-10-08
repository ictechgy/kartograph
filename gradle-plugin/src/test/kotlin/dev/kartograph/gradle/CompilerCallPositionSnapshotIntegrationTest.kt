package dev.kartograph.gradle

import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeId
import dev.kartograph.export.QuerySnapshotCodec
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

class CompilerCallPositionSnapshotIntegrationTest {
    @Test
    fun `completed javac v3 receipt is imported into the snapshot`(@TempDir root: Path) {
        val collector = collector()
        javaFixture(root, collector)

        val result = build(root, "callPositionSnapshot", "-PcallPositions=true")
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":callPositionSnapshot")?.outcome)
        val snapshot = snapshot(root)
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        val position = snapshot.graph.locatedCompilerReferences.single { it.target == NodeId("method:demo/Target#hit()V") }
        assertEquals(NodeId("method:demo/Caller#use()V"), position.source)
        assertEquals("src/main/java/demo/Caller.java", position.file.path)
        assertEquals(CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, position.coordinateBasis)
        assertEquals("javac-constants", position.collector)
        assertEquals("hit", Files.readString(root.resolve(position.file.path)).substring(position.offsetUtf16, position.endOffsetUtf16))
        assertEquals("src/main/java/demo/Caller.java", snapshot.graph.nodes.getValue(position.source).location?.path)
        assertTrue(snapshot.graph.edges.any { it.source == NodeId("method:demo/Caller#use()V") &&
            it.target == NodeId("field:demo/Target#VALUE:I") && it.origin == EdgeOrigin.COMPILER_REFERENCE })
        assertTrue(snapshot.provenance!!.witnesses.single().compilerEvidence.any { it.role == "compilerEvidence" })
    }

    @Test
    fun `javac snapshot follows true false true through configuration and build cache`(@TempDir root: Path) {
        javaFixture(root, collector())
        val first = build(root, "callPositionSnapshot", "-PcallPositions=true")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJava")?.outcome)
        val enabled = Files.readString(root.resolve("snapshot.json"))
        assertTrue(QuerySnapshotCodec.parse(enabled).graph.compilerCallPositionsCaptured)

        val again = build(root, "callPositionSnapshot", "-PcallPositions=true")
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")?.outcome)
        assertEquals(enabled, Files.readString(root.resolve("snapshot.json")))

        assertEquals(TaskOutcome.SUCCESS, build(root, "callPositionSnapshot", "-PcallPositions=false")
            .task(":compileJava")?.outcome)
        assertFalse(snapshot(root).graph.compilerCallPositionsCaptured)

        val restored = build(root, "callPositionSnapshot", "-PcallPositions=true")
        assertTrue(restored.task(":compileJava")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
        assertTrue(snapshot(root).graph.compilerCallPositionsCaptured)
        build(root, "clean", "-PcallPositions=true")
        val cached = build(root, "callPositionSnapshot", "-PcallPositions=true")
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":compileJava")?.outcome)
        assertTrue(snapshot(root).graph.compilerCallPositionsCaptured)
    }

    @Test
    fun `captured empty and unreceipted raw files preserve completed receipt authority`(@TempDir root: Path) {
        javaFixture(root, collector(), emptyCalls = true)
        build(root, "callPositionSnapshot", "-PcallPositions=true", "-PrawEvidence=true")
        val snapshot = snapshot(root)
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        assertTrue(snapshot.graph.locatedCompilerReferences.isEmpty())
        assertEquals("src/main/java/demo/Caller.java",
            snapshot.graph.nodes.getValue(NodeId("method:demo/Caller#use()V")).location?.path)
        assertTrue(Files.isRegularFile(root.resolve("build/kartograph/compiler-evidence/compileJava/unreceipted.tsv")))
    }

    @Test
    fun `changed completed receipt prevents snapshot publication`(@TempDir root: Path) {
        javaFixture(root, collector())
        val result = build(root, "callPositionSnapshot", "-PcallPositions=true", "-PtamperEvidence=true", fails = true)
        assertContains(result.output, "compiler evidence does not match the managed output")
        assertFalse(Files.exists(root.resolve("snapshot.json")))
    }

    @Test
    fun `failed evidence recapture preserves published snapshot and bindings bytes`(@TempDir root: Path) {
        javaFixture(root, collector())
        build(root, "callPositionSnapshot", "-PcallPositions=true")
        val snapshot = Files.readAllBytes(root.resolve("snapshot.json"))
        val bindings = Files.readAllBytes(root.resolve("bindings.json"))

        val failed = build(root, "callPositionSnapshot", "-PcallPositions=true", "-PtamperEvidence=true", fails = true)
        assertContains(failed.output, "compiler evidence does not match the managed output")
        assertTrue(snapshot.contentEquals(Files.readAllBytes(root.resolve("snapshot.json"))))
        assertTrue(bindings.contentEquals(Files.readAllBytes(root.resolve("bindings.json"))))
    }

    @Test
    fun `managed evidence candidates enforce count and per document byte limits`(@TempDir root: Path) {
        val count = Files.createDirectories(root.resolve("count"))
        javaFixture(count, collector())
        val tooMany = build(count, "callPositionSnapshot", "-PoverflowEvidence=true", fails = true)
        assertContains(tooMany.output, "compiler evidence inputs exceed the document limit")
        assertFalse(Files.exists(count.resolve("snapshot.json")))

        val size = Files.createDirectories(root.resolve("size"))
        javaFixture(size, collector())
        val tooLarge = build(size, "callPositionSnapshot", "-PoversizeEvidence=true", fails = true)
        assertContains(tooLarge.output, "compiler evidence input exceeds the document byte limit")
        assertFalse(Files.exists(size.resolve("snapshot.json")))
    }

    @Test
    fun `external build directory binds only its completed compiler evidence`(@TempDir root: Path) {
        val project = Files.createDirectories(root.resolve("project"))
        val external = root.resolve("external-build")
        javaFixture(project, collector(), externalBuild = external)
        build(project, "callPositionSnapshot", "-PcallPositions=true")
        val text = Files.readString(project.resolve("snapshot.json"))
        val snapshot = QuerySnapshotCodec.parse(text)
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        val receipt = snapshot.provenance!!.witnesses.single().compilerEvidence.single { it.role == "compilerEvidence" }
        assertTrue(receipt.path.startsWith("external/"))
        val bindings = dev.kartograph.export.ExternalInputBindingsCodec.parse(Files.readString(project.resolve("bindings.json")))
        assertEquals(external.resolve("kartograph/compiler-evidence/compileJava/references.tsv").toRealPath(),
            Path.of(bindings.getValue(receipt.path)).toRealPath())
        assertFalse(text.contains(external.toString()))
    }

    @Test
    fun `external evidence rejects changed and duplicate receipt candidates`(@TempDir root: Path) {
        val changed = Files.createDirectories(root.resolve("changed"))
        javaFixture(changed, collector(), externalBuild = root.resolve("changed-build"))
        val tampered = build(changed, "callPositionSnapshot", "-PtamperEvidence=true", fails = true)
        assertContains(tampered.output, "compiler evidence binding is missing")
        assertFalse(Files.exists(changed.resolve("snapshot.json")))

        val duplicate = Files.createDirectories(root.resolve("duplicate"))
        javaFixture(duplicate, collector(), externalBuild = root.resolve("duplicate-build"))
        val ambiguous = build(duplicate, "callPositionSnapshot", "-PduplicateEvidence=true", fails = true)
        assertContains(ambiguous.output, "compiler evidence binding is ambiguous")
        assertFalse(Files.exists(duplicate.resolve("snapshot.json")))
    }

    @Test
    fun `completed Kotlin v3 receipt survives configuration cache in the snapshot`(@TempDir root: Path) {
        val (gradleClasspath, compilerClasspath) = kotlinClasspaths()
        kotlinFixture(root, collector(), gradleClasspath, compilerClasspath)
        val first = build(root, "callPositionSnapshot")
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome)
        val text = Files.readString(root.resolve("snapshot.json"))
        val snapshot = QuerySnapshotCodec.parse(text)
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        val position = snapshot.graph.locatedCompilerReferences.single { it.target == NodeId("method:demo/Target#hit()V") }
        assertEquals(NodeId("method:demo/CallerKt#use()V"), position.source)
        assertEquals(CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, position.coordinateBasis)
        assertEquals("2.4.10", position.compilerVersion)
        assertEquals("src/main/kotlin/demo/Caller.kt", snapshot.graph.nodes.getValue(position.source).location?.path)

        val again = build(root, "callPositionSnapshot")
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileKotlin")?.outcome)
        assertEquals(text, Files.readString(root.resolve("snapshot.json")))
    }

    @Test
    fun `automatic snapshot ignores stale managed files when no receipt exists`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='no-evidence-snapshot'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            kartograph { snapshotsEnabled = true }
        """.trimIndent())
        val source = Files.createDirectories(root.resolve("src/main/java/demo"))
        Files.writeString(source.resolve("Entry.java"), "package demo; public class Entry {}")
        val stale = Files.createDirectories(root.resolve("build/kartograph/compiler-evidence/compileJava"))
        Files.writeString(stale.resolve(".DS_Store"), "not compiler evidence")

        val result = build(root, "kartographSnapshot")
        assertEquals(TaskOutcome.SUCCESS, result.task(":kartographSnapshot")?.outcome)
        assertFalse(QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
            .graph.compilerCallPositionsCaptured)
    }

    @Test
    fun `mixed witnesses ignore stale managed directory without a completed receipt`(@TempDir root: Path) {
        javaFixture(root, collector(), mixedWitness = true)
        val result = build(root, "callPositionSnapshot", "-PcallPositions=true")
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileTestJava")?.outcome)
        val snapshot = snapshot(root)
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        assertTrue(NodeId("class:demo/Check") in snapshot.graph.nodes)
        assertTrue(Files.isRegularFile(root.resolve("build/kartograph/compiler-evidence/compileTestJava/.DS_Store")))
    }

    private fun javaFixture(root: Path, collector: Path, emptyCalls: Boolean = false, externalBuild: Path? = null,
        mixedWitness: Boolean = false) {
        Files.createDirectories(root.resolve("src/main/java/demo"))
        Files.writeString(root.resolve("src/main/java/demo/Caller.java"),
            if (emptyCalls) "package demo; public class Caller { public void use() {} }"
            else "package demo; public class Caller { public void use() { int value = Target.VALUE; Target.hit(); } }")
        Files.writeString(root.resolve("src/main/java/demo/Target.java"),
            "package demo; public class Target { public static final int VALUE = 1; public static void hit() {} }")
        if (mixedWitness) {
            Files.createDirectories(root.resolve("src/test/java/demo"))
            Files.writeString(root.resolve("src/test/java/demo/Check.java"),
                "package demo; public class Check { public void check() { new Caller().use(); } }")
        }
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name='call-position-snapshot'\nbuildCache { local { directory=file('.fixture-cache') } }\n")
        val buildDirectory = externalBuild?.let { "layout.buildDirectory.set(file('${groovy(it)}'))" }.orEmpty()
        val mixedSetup = if (mixedWitness) """
            def testCompiler = tasks.named('compileTestJava', JavaCompile)
            def testSources = files('src/test/java')
            def testEvidenceDirectory = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, testCompiler)
            testCompiler.configure { classpath = files(compiler.flatMap { it.destinationDirectory }) }
            def testWitness = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, testCompiler, 'sample:main',
                testSources, buildInputs, files(), false)
            testCompiler.configure {
                doLast {
                    def stale = testEvidenceDirectory.get().asFile
                    stale.mkdirs()
                    new File(stale, '.DS_Store').text = 'not compiler evidence'
                }
            }
        """.trimIndent() else ""
        val mixedTask = if (mixedWitness) """
                def testCompilation = objects.newInstance(dev.kartograph.gradle.SnapshotCompilation)
                testCompilation.identity.set(testCompiler.map { it.path })
                testCompilation.compiler.set('javac')
                testCompilation.primarySources.from(testCompiler.map { it.source })
                testCompilation.classDirectories.from(testCompiler.flatMap { it.destinationDirectory })
                testCompilation.witnessFiles.from(testWitness)
                task.compilations.add(testCompilation)
                task.classRoots.from(testCompiler.flatMap { it.destinationDirectory })
                task.dependencyClasspath.from(testCompiler.map { it.classpath })
                task.sourceDirectories.from(testSources)
                task.sourceFiles.from(testCompiler.map { it.source })
                task.buildWitnessFiles.from(testWitness)
                task.compilerInputFiles.from(testCompiler.map { it.classpath },
                    testCompiler.flatMap { it.javaCompiler }.map { it.metadata.installationPath.file('lib/modules') })
        """.trimIndent() else ""
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            $buildDirectory
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            def compiler = tasks.named('compileJava', JavaCompile)
            def sources = files('src/main/java')
            def buildInputs = files('build.gradle', 'settings.gradle')
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def evidenceDirectory = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
            def evidence = evidenceDirectory.map { it.file('references.tsv') }
            def enabled = providers.gradleProperty('callPositions').getOrElse('true')
            def rawEvidence = providers.gradleProperty('rawEvidence').isPresent()
            def tamperEvidence = providers.gradleProperty('tamperEvidence').isPresent()
            def overflowEvidence = providers.gradleProperty('overflowEvidence').isPresent()
            def oversizeEvidence = providers.gradleProperty('oversizeEvidence').isPresent()
            def duplicateEvidence = providers.gradleProperty('duplicateEvidence').isPresent()
            compiler.configure {
                options.annotationProcessorPath = files('${groovy(collector)}')
                inputs.property('fixtureRawEvidence', rawEvidence)
                inputs.property('fixtureTamperEvidence', tamperEvidence)
                inputs.property('fixtureOverflowEvidence', overflowEvidence)
                inputs.property('fixtureOversizeEvidence', oversizeEvidence)
                inputs.property('fixtureDuplicateEvidence', duplicateEvidence)
                options.compilerArgs.add('-Xplugin:KartographEvidence collector=javac-constants' +
                    ' root=${root.toUri()}' + ' output=' + evidence.get().asFile.toPath().toUri().toASCIIString() +
                    ' token=' + token.get().asFile.toPath().toUri().toASCIIString() + ' callPositions=' + enabled)
            }
            def witness = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, compiler, 'sample:main',
                sources, buildInputs, files(), true)
            compiler.configure {
                doLast {
                    if (rawEvidence) new File(evidenceDirectory.get().asFile, 'unreceipted.tsv').text = 'not compiler evidence'
                    if (tamperEvidence) evidence.get().asFile.append('tampered')
                    if (overflowEvidence) 256.times { index ->
                        new File(evidenceDirectory.get().asFile, 'raw-' + index + '.tsv').text = 'not compiler evidence'
                    }
                    if (oversizeEvidence) {
                        def raw = new File(evidenceDirectory.get().asFile, 'oversized.tsv')
                        new RandomAccessFile(raw, 'rw').withCloseable { it.setLength(16 * 1024 * 1024 + 1) }
                    }
                    if (duplicateEvidence) java.nio.file.Files.copy(evidence.get().asFile.toPath(),
                        new File(evidenceDirectory.get().asFile, 'duplicate.tsv').toPath())
                }
            }
            $mixedSetup
            tasks.register('callPositionSnapshot', dev.kartograph.gradle.KartographSnapshotTask) { task ->
                def compilation = objects.newInstance(dev.kartograph.gradle.SnapshotCompilation)
                compilation.identity.set(compiler.map { it.path })
                compilation.compiler.set('javac')
                compilation.primarySources.from(compiler.map { it.source })
                compilation.classDirectories.from(compiler.flatMap { it.destinationDirectory })
                compilation.witnessFiles.from(witness)
                task.compilations.add(compilation)
                task.classRoots.from(compiler.flatMap { it.destinationDirectory })
                task.dependencyClasspath.from(compiler.map { it.classpath })
                task.sourceDirectories.from(sources)
                task.sourceFiles.from(compiler.map { it.source })
                task.buildInputFiles.from(buildInputs)
                task.buildWitnessFiles.from(witness)
                task.compilerInputFiles.from(files('${groovy(collector)}'), compiler.map { it.classpath },
                    compiler.flatMap { it.javaCompiler }.map { it.metadata.installationPath.file('lib/modules') })
                task.scope.set('sample:main')
                task.includeSourcePaths.set(true)
                task.includePrivateMembers.set(false)
                task.indexCacheEnabled.set(false)
                task.projectDirectory.set(layout.projectDirectory)
                task.buildDirectory.set(layout.buildDirectory)
                task.snapshotFile.set(layout.projectDirectory.file('snapshot.json'))
                task.localBindingsFile.set(layout.projectDirectory.file('bindings.json'))
                $mixedTask
            }
        """.trimIndent())
    }

    private fun kotlinFixture(root: Path, collector: Path, gradleClasspath: List<Path>, compilerClasspath: List<Path>) {
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("src/main/kotlin/demo/Caller.kt"),
            "package demo\nfun use() { Target().hit() }\n")
        Files.writeString(root.resolve("src/main/kotlin/demo/Target.kt"),
            "package demo\nclass Target { fun hit() {} }\n")
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name='kotlin-call-position-snapshot'\nbuildCache { local { directory=file('.fixture-cache') } }\n")
        Files.writeString(root.resolve("gradle.properties"),
            "kotlin.stdlib.default.dependency=false\nkotlin.compiler.runViaBuildToolsApi=false\n")
        val repository = kotlinRepository(root, gradleClasspath)
        val buildscriptClasspath = gradleClasspath.joinToString(", ") { "'${groovy(it)}'" }
        val compilerRuntime = compilerClasspath.joinToString(", ") { "'${groovy(it)}'" }
        val stdlib = compilerClasspath.single { it.fileName.toString() == "kotlin-stdlib-2.4.10.jar" }
        Files.writeString(root.resolve("build.gradle"), """
            buildscript { dependencies { classpath files($buildscriptClasspath) } }
            plugins { id 'io.github.ictechgy.kartograph' }
            apply plugin: 'org.jetbrains.kotlin.jvm'
            repositories { maven { url = uri('${groovy(repository)}') } }
            dependencies { implementation project.files('${groovy(stdlib)}') }
            configurations.configureEach {
                exclude group: 'org.jetbrains.kotlin', module: 'kotlin-scripting-compiler-embeddable'
            }
            configurations.named('kotlinCompilerClasspath') {
                dependencies.clear()
                dependencies.add(project.dependencies.create(project.files($compilerRuntime)))
            }
            configurations.named('kotlinBuildToolsApiClasspath') {
                dependencies.clear()
                dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.10'))
                dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.10'))
                dependencies.add(project.dependencies.create(project.files($compilerRuntime)))
            }
            configurations.named('kotlinCompilerPluginClasspathMain') { dependencies.clear() }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            def compiler = tasks.named('compileKotlin')
            def sources = files('src/main/kotlin')
            def buildInputs = files('build.gradle', 'settings.gradle', 'gradle.properties')
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def evidenceDirectory = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
            def evidence = evidenceDirectory.map { it.file('references.tsv') }
            compiler.configure {
                pluginClasspath.from(files('${groovy(collector)}'))
                compilerOptions.freeCompilerArgs.addAll(
                    '-P', 'plugin:kartograph.compiler-evidence:root=${root}',
                    '-P', 'plugin:kartograph.compiler-evidence:output=' + evidence.get().asFile.absolutePath,
                    '-P', 'plugin:kartograph.compiler-evidence:token=' + token.get().asFile.absolutePath,
                    '-P', 'plugin:kartograph.compiler-evidence:callPositions=true')
            }
            def jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
            def witness = dev.kartograph.gradle.KotlinCompilerWitnesses.INSTANCE.kotlinCompile(project, compiler, 'sample:main',
                sources, buildInputs, jdk, files($compilerRuntime), true)
            tasks.register('callPositionSnapshot', dev.kartograph.gradle.KartographSnapshotTask) { task ->
                def compilation = objects.newInstance(dev.kartograph.gradle.SnapshotCompilation)
                compilation.identity.set(compiler.map { it.path })
                compilation.compiler.set('kotlin')
                compilation.primarySources.from(compiler.map { it.sources })
                compilation.classDirectories.from(compiler.flatMap { it.destinationDirectory })
                compilation.witnessFiles.from(witness)
                task.compilations.add(compilation)
                task.classRoots.from(compiler.flatMap { it.destinationDirectory })
                task.dependencyClasspath.from(compiler.map { it.libraries })
                task.sourceDirectories.from(sources)
                task.sourceFiles.from(compiler.map { it.sources })
                task.buildInputFiles.from(buildInputs)
                task.buildWitnessFiles.from(witness)
                task.compilerInputFiles.from(files($compilerRuntime), files($buildscriptClasspath), files('${groovy(collector)}'),
                    jdk.map { it.metadata.installationPath.file('lib/modules') })
                task.scope.set('sample:main')
                task.includeSourcePaths.set(true)
                task.includePrivateMembers.set(false)
                task.indexCacheEnabled.set(false)
                task.projectDirectory.set(layout.projectDirectory)
                task.buildDirectory.set(layout.buildDirectory)
                task.snapshotFile.set(layout.projectDirectory.file('snapshot.json'))
                task.localBindingsFile.set(layout.projectDirectory.file('bindings.json'))
            }
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

    private fun kotlinClasspaths(): Pair<List<Path>, List<Path>> {
        fun paths(name: String): List<Path> {
            val value = System.getenv(name)
            assumeTrue(!value.isNullOrBlank(), "$name is required")
            return value.split(File.pathSeparator).filter(String::isNotBlank).map { Path.of(it).toRealPath() }
                .also { assumeTrue(it.isNotEmpty() && it.all(Files::isRegularFile)) }
        }
        return paths("KARTOGRAPH_KOTLIN_GRADLE_CLASSPATH") to paths("KARTOGRAPH_KOTLIN_COMPILER_CLASSPATH")
    }

    private fun build(root: Path, vararg arguments: String, fails: Boolean = false): org.gradle.testkit.runner.BuildResult {
        val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments(arguments.toList() + listOf("--configuration-cache", "--build-cache", "--offline", "--stacktrace"))
        return if (fails) runner.buildAndFail() else runner.build()
    }

    private fun snapshot(root: Path) = QuerySnapshotCodec.parse(Files.readString(root.resolve("snapshot.json")))

    private fun collector(): Path {
        val value = System.getenv("KARTOGRAPH_COLLECTOR_JAR")
        assumeTrue(!value.isNullOrBlank(), "packaged compiler collector is required")
        return Path.of(value).toRealPath().also { assumeTrue(Files.isRegularFile(it)) }
    }

    private fun groovy(path: Path): String = path.toString().replace("\\", "\\\\").replace("'", "\\'")
}
