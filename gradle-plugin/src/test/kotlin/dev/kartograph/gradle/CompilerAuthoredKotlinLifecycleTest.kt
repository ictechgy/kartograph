package dev.kartograph.gradle

import dev.kartograph.core.*
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** byte-identical Kotlin 산출물도 원문/완료 receipt를 바꾸면 이전 source 사실을 재사용할 수 없다. */
class CompilerAuthoredKotlinLifecycleTest {
    @Test fun `Kotlin 2420 collision binds exact source successful output and complete zero`(@TempDir root: Path) {
        val collector = configured("KARTOGRAPH_COLLECTOR_JAR").single()
        val gradleClasspath = configured("KARTOGRAPH_AUTHORED_KOTLIN_GRADLE_CLASSPATH")
        val buildToolsDependencies = setOf("kotlin-tooling-core", "kotlin-build-tools-cri-impl", "kotlin-compiler-runner", "kotlin-daemon-client")
        val runtime = (configured("KARTOGRAPH_AUTHORED_KOTLIN_COMPILER_CLASSPATH") + gradleClasspath.filter {
            it.fileName.toString().removeSuffix("-2.4.20.jar") in buildToolsDependencies
        }).distinct()
        val stdlib = runtime.single { it.fileName.toString() == "kotlin-stdlib-2.4.20.jar" }
        val sdk = Path.of(System.getProperty("java.home")).toRealPath()
        val source = Files.createDirectories(root.resolve("src/main/kotlin/collision")).resolve("Collision.kt")
        val implicit = "package collision\nclass Printable { override fun toString(): String = \"printable\" }\nobject Calls { @JvmStatic fun render(receiver: Printable): String = \"value=${'$'}receiver\" }\n"
        val explicit = implicit.replace("${'$'}receiver", "${'$'}{receiver.toString()}")
        Files.writeString(source, implicit)
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='authored-kotlin'\nbuildCache { local { directory=file('.fixture-cache') } }")
        Files.writeString(root.resolve("gradle.properties"), "kotlin.stdlib.default.dependency=false\nkotlin.compiler.runViaBuildToolsApi=false\nkotlin.compiler.execution.strategy=in-process\n")
        val repository = root.resolve(".kotlin-repository")
        for (artifact in listOf("kotlin-build-tools-compat", "kotlin-build-tools-impl")) {
            val jar = gradleClasspath.single { it.fileName.toString() == "$artifact-2.4.20.jar" }
            val dir = Files.createDirectories(repository.resolve("org/jetbrains/kotlin/$artifact/2.4.20"))
            Files.copy(jar, dir.resolve(jar.fileName))
            Files.writeString(dir.resolve("$artifact-2.4.20.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.jetbrains.kotlin</groupId><artifactId>$artifact</artifactId><version>2.4.20</version></project>")
        }
        fun list(paths: List<Path>) = paths.joinToString(", ") { "'${q(it)}'" }
        Files.writeString(root.resolve("build.gradle"), """
            buildscript { dependencies { classpath files(${list(gradleClasspath)}) } }
            plugins { id 'io.github.ictechgy.kartograph' }
            apply plugin: 'org.jetbrains.kotlin.jvm'
            repositories { maven { url = uri('${q(repository)}') } }
            dependencies { implementation project.files('${q(stdlib)}') }
            configurations.configureEach { exclude group: 'org.jetbrains.kotlin', module: 'kotlin-scripting-compiler-embeddable' }
            configurations.named('kotlinCompilerClasspath') {
                dependencies.clear()
                dependencies.add(project.dependencies.create(project.files(${list(runtime)})))
            }
            configurations.named('kotlinBuildToolsApiClasspath') {
                dependencies.clear()
                dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.20'))
                dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.20'))
                dependencies.add(project.dependencies.create(project.files(${list(runtime)})))
            }
            configurations.named('kotlinCompilerPluginClasspathMain') { dependencies.clear() }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            def compiler = tasks.named('compileKotlin')
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def output = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
            compiler.configure {
                pluginClasspath.from(files('${q(collector)}'))
                compilerOptions.freeCompilerArgs.add('-Xstring-concat=indy-with-constants')
                ['root=${root}', 'output=' + output.get().asFile.absolutePath,
                 'token=' + token.get().asFile.absolutePath, 'callPositions=true', 'authoredCalls=true'].each { option ->
                    compilerOptions.freeCompilerArgs.addAll('-P', 'plugin:kartograph.compiler-evidence:' + option)
                }
            }
            def jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
            dev.kartograph.gradle.KotlinCompilerWitnesses.INSTANCE.kotlinCompile(project, compiler, 'sample:main',
                files('src/main/kotlin'), files('build.gradle','settings.gradle','gradle.properties'), jdk,
                files(${list(runtime)}, '${q(sdk.resolve("jmods"))}', '${q(sdk.resolve("release"))}'), true)
        """.trimIndent())
        fun run(vararg args: String, fails: Boolean = false): org.gradle.testkit.runner.BuildResult {
            val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(args.toList() +
                listOf("--offline", "--configuration-cache", "--build-cache", "--stacktrace"))
            return if (fails) runner.buildAndFail() else runner.build()
        }
        fun preserve(stage: String) {
            val configured = System.getenv("KARTOGRAPH_AUTHORED_RECEIPT_DIR") ?: return
            val destination = Files.createDirectories(Path.of(configured).resolve("kotlin-$stage"))
            for (relative in listOf("src", "build/classes", "build/kartograph", "build.gradle", "settings.gradle", "gradle.properties")) {
                val input = root.resolve(relative)
                Files.walk(input).use { paths -> paths.forEach { path ->
                    val target = destination.resolve(root.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(target) else {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                } }
            }
        }
        val classes = root.resolve("build/classes/kotlin/main")
        val witnessPath = root.resolve("build/kartograph/witnesses/compileKotlin/witness.json")
        fun load(): CompilerEvidenceEnrichment {
            val witness = BuildWitnessCodec.parse(Files.readString(witnessPath))
            assertTrue(CompilerAuthoredCallOptions.isEnabled(witness.inputs))
            assertTrue(CompilerCallPositionOptions.isEnabled(witness.inputs))
            assertTrue(CompilerEvidenceToken.matches(witness))
            val candidates = (runtime + gradleClasspath + listOf(collector, sdk.resolve("lib/modules"), sdk.resolve("jmods"), sdk.resolve("release"))).distinct()
            val byHash = candidates.groupBy { ContentFingerprint.hashInput(it, if (Files.isDirectory(it)) "compilerInput" else "compiler") }
            val external = witness.inputs.filter { it.path.startsWith("external/") }.associate { input ->
                input.path to requireNotNull(byHash[input.sha256]).first()
            }
            val proof = SnapshotProvenance(witness.outputs + ContentFingerprint.capture(root, witnessPath, "witness", "compileKotlin"), listOf(witness))
            val context = CompilerEvidenceContext(root, "sample:main", proof, external)
            val docs = witness.compilerEvidence.filter { it.role == "compilerEvidence" }.map { root.resolve(it.path) }
            assertEquals(2, docs.size)
            val authored = docs.single { it.fileName.toString().endsWith("-authored.tsv") }
            assertEquals("2.4.20", CompilerAuthoredCallReader.read(authored).evidence.compilerVersion)
            return CompilerEvidenceIndexer.indexWithAuthoredCalls(listOf(classes), listOf(stdlib), sdk, docs, context)
        }
        val render = NodeId("method:collision/Calls#render(Lcollision/Printable;)Ljava/lang/String;")
        val toString = NodeId("method:collision/Printable#toString()Ljava/lang/String;")
        assertEquals(TaskOutcome.SUCCESS, run("compileKotlin").task(":compileKotlin")?.outcome)
        val first = load().result.graph
        assertEquals(AuthoredCallCoverage.COMPLETE, first.compilerAuthoredCallsFrom(render)?.coverage)
        assertEquals(emptyList(), first.compilerAuthoredCallsFrom(render)?.calls)
        assertEquals(emptyList(), first.compilerAuthoredCallsFrom(toString)?.calls)
        preserve("implicit")
        val classesHash = ContentFingerprint.hashInput(classes, "classes")
        val receipt = Files.readString(witnessPath)
        assertEquals(TaskOutcome.UP_TO_DATE, run("compileKotlin").task(":compileKotlin")?.outcome)
        run("clean")
        assertEquals(TaskOutcome.FROM_CACHE, run("compileKotlin").task(":compileKotlin")?.outcome)
        assertEquals(receipt, Files.readString(witnessPath))
        assertEquals(first.compilerAuthoredCallers, load().result.graph.compilerAuthoredCallers)
        Files.writeString(source, explicit)
        assertEquals(classesHash, ContentFingerprint.hashInput(classes, "classes"))
        assertFailsWith<IllegalArgumentException> { load() }
        assertEquals(TaskOutcome.SUCCESS, run("compileKotlin").task(":compileKotlin")?.outcome)
        assertEquals(classesHash, ContentFingerprint.hashInput(classes, "classes"))
        val second = load().result.graph
        preserve("explicit")
        assertEquals(AuthoredCallCoverage.COMPLETE, second.compilerAuthoredCallsFrom(render)?.coverage)
        assertEquals(listOf(toString), second.compilerAuthoredCallsFrom(render)?.calls?.map { it.target })
        assertEquals(first.nodes, second.nodes)
        assertEquals(first.edges, second.edges)
        assertEquals(first.externalCalls, second.externalCalls)
        assertNotEquals(receipt, Files.readString(witnessPath))
        Files.writeString(source, "syntax error invalid source")
        run("compileKotlin", fails = true)
        assertFalse(Files.exists(witnessPath))
    }
    private fun configured(name: String): List<Path> {
        val value = System.getenv(name)
        assumeTrue(!value.isNullOrBlank(), "pinned authored collector/compiler classpath is required: $name")
        return value.split(File.pathSeparator).filter(String::isNotBlank).map { Path.of(it).toRealPath() }
    }
    private fun q(path: Path): String = path.toString().replace("\\", "\\\\").replace("'", "\\'")
}
