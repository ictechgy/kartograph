package dev.kartograph.gradle

import dev.kartograph.export.McpJsonCodec
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.io.TempDir

class DependencyTaskIntegrationTest {
    @Test
    fun `dependency task reuses configuration cache and invalidates changed scopes and class bytes`(@TempDir root: Path) {
        exercise(root, inProcess = false)
    }

    @Test
    fun `instrumented dependency task preserves advice strict failure and test scope`(@TempDir root: Path) {
        exercise(root, inProcess = true)
    }

    @Test
    fun `unrequested dependency analysis does not resolve configuration artifacts`(@TempDir root: Path) {
        root.resolve("settings.gradle").writeText("rootProject.name='lazy-dependency-fixture'\n")
        root.resolve("build.gradle").writeText("""
            plugins { id 'java-library'; id 'io.github.ictechgy.kartograph' }
            dependencies { implementation 'missing.fixture:must-not-resolve:1' }
        """.trimIndent())
        val result = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("help", "--offline", "--configuration-cache").build()
        assertTrue(result.task(":kartographDependencies") == null)
        assertFalse(Files.exists(root.resolve("build/reports/kartograph/jvm-dependencies.txt")))
    }

    @Test
    fun `variant processor bucket names and later-added declarations are observed without resolution`(@TempDir root: Path) {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        val requests = DependencyTasks.requests(project, listOf("", "debug"), listOf("test", "debugUnitTest"))
        project.configurations.create("kaptDebug")
        project.configurations.create("kspDebugUnitTest")
        project.dependencies.add("kaptDebug", "example:processor:1")
        project.dependencies.add("kspDebugUnitTest", "example:symbols:1")
        assertContains(requests.get(), "kapt\tmodule:example:processor")
        assertContains(requests.get(), "ksp\tmodule:example:symbols")
    }

    @Test
    fun `dependency task inputs do not record checkout or Gradle user home locations`(@TempDir root: Path) {
        val jar = classJar(root.resolve("jar"), "shared/Shared", "package shared; public class Shared {}")
        fun inputs(name: String): Map<String, Any?> {
            val checkout = root.resolve("$name/checkout")
            val home = root.resolve("$name/gradle-home")
            copy(jar, checkout.resolve("libs/local.jar"))
            copy(jar, home.resolve("kartograph-fixture/shared.jar"))
            // Gradle은 user home을 실제 경로로 바꿔 두므로, 별칭 경로로 적은 artifact도 같은 기준으로 읽어야 한다.
            val alias = Files.createSymbolicLink(root.resolve("$name/home-alias"), home)
            // project 의존성은 산출 task가 끝나기 전에 입력을 읽을 수 없으므로 TestKit 시험이 다룬다.
            val project = ProjectBuilder.builder().withName("consumer").withProjectDir(checkout.toFile())
                .withGradleUserHomeDir(home.toFile()).build()
            project.pluginManager.apply("java-library")
            project.pluginManager.apply(KartographPlugin::class.java)
            project.dependencies.add("implementation", project.files("libs/local.jar"))
            project.dependencies.add("implementation", project.files(alias.resolve("kartograph-fixture/shared.jar")))
            // ProjectBuilder는 afterEvaluate를 실행하지 않으므로 plugin이 그때 하는 등록을 직접 부른다.
            DependencyTasks.registerJvm(project, project.extensions.getByType(KartographExtension::class.java))
            val properties = project.tasks.named("kartographDependencies", KartographDependenciesTask::class.java).get().inputs.properties
            for ((property, value) in properties) {
                for (location in listOf(checkout, home, alias)) {
                    assertFalse(value.toString().contains(location.toString()), "$property records a local location: $value")
                }
            }
            return properties
        }
        val first = inputs("first")
        val second = inputs("second")
        assertEquals(first, second)
        val declarations = first.getValue("declarations") as List<*>
        assertTrue(declarations.any { it.toString().startsWith("file:local.jar#") }, declarations.toString())
        assertTrue(declarations.any { it.toString().startsWith("file:shared.jar#") }, declarations.toString())
    }

    @Test
    fun `dependency reports restored from the build cache in another checkout and Gradle user home match a fresh run`(@TempDir root: Path) {
        val shared = classJar(root.resolve("jars/shared"), "shared/Shared", "package shared; public class Shared {}")
        val local = classJar(root.resolve("jars/local"), "local/Local", "package local; public class Local {}")
        fun checkout(name: String): Pair<Path, Path> {
            val checkout = root.resolve("$name/repository")
            val home = root.resolve("$name/gradle-home")
            write(checkout, "settings.gradle", "rootProject.name = 'relocated-dependencies'\ninclude 'library', 'consumer'\n" +
                "buildCache { local { directory = file('../../build-cache') } }\n")
            write(checkout, "build.gradle", """
                subprojects {
                  apply plugin: 'java-library'
                  java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
                }
            """.trimIndent() + "\n" + OwnedTestKitHome.identityTask)
            write(checkout, "library/src/main/java/library/Library.java", "package library; public class Library {}")
            write(checkout, "consumer/build.gradle", """
                plugins { id 'io.github.ictechgy.kartograph' }
                dependencies {
                  implementation project(':library')
                  implementation files('libs/local.jar')
                  implementation files("${'$'}{gradle.gradleUserHomeDir}/kartograph-fixture/shared.jar")
                }
                kartograph { reportFormat = 'json' }
                tasks.withType(dev.kartograph.gradle.KartographDependenciesTask).configureEach {
                  baselineOutput = layout.buildDirectory.file('kartograph/captured-dependencies.json')
                }
            """.trimIndent())
            write(checkout, "consumer/src/main/java/consumer/Api.java",
                "package consumer; public class Api { public library.Library library; public shared.Shared shared; }")
            copy(local, checkout.resolve("consumer/libs/local.jar"))
            copy(shared, home.resolve("kartograph-fixture/shared.jar"))
            return checkout to home
        }
        fun build(checkout: Path, home: OwnedTestKitHome, vararg options: String): TaskOutcome? = home.build(GradleRunner.create()
            .withProjectDir(checkout.toFile()).withPluginClasspath()
            .withArguments(listOf(OwnedTestKitHome.IDENTITY_TASK, ":consumer:kartographDependencies") + options +
                listOf("--build-cache", "--offline", "--configuration-cache", "--stacktrace")))
            .task(":consumer:kartographDependencies")?.outcome
        fun outputs(checkout: Path): List<String> = listOf("consumer/build/reports/kartograph/jvm-dependencies.txt",
            "consumer/build/kartograph/captured-dependencies.json").map { Files.readString(checkout.resolve(it)) }
        val (first, firstHome) = checkout("first")
        OwnedTestKitHome(root, firstHome, first).use { firstDaemon ->
            assertEquals(TaskOutcome.SUCCESS, build(first, firstDaemon))
            val original = outputs(first)
            val (second, secondHome) = checkout("second")
            OwnedTestKitHome(root, secondHome, second).use { secondDaemon ->
                val restored = build(second, secondDaemon)
                val restoredOutputs = outputs(second)
                val rerun = build(second, secondDaemon, "--rerun")
                val fresh = outputs(second)
                val locations = listOf(first, firstHome, second, secondHome).map(Path::toString)
                val diagnostics = ((McpJsonCodec.parse(original[0]) as Map<*, *>)["diagnostics"] as List<*>).map { it as Map<*, *> }
                assertAll(
                    { assertEquals(TaskOutcome.FROM_CACHE, restored, "the relocated checkout must reuse the cached dependency review") },
                    { assertEquals(TaskOutcome.SUCCESS, rerun) },
                    { assertEquals(original, fresh, "a fresh run in another checkout must write the same report and fingerprints") },
                    { assertEquals(fresh, restoredOutputs) },
                    { assertTrue(original[0].contains("file:local.jar#") && original[0].contains("file:shared.jar#"), original[0]) },
                    // 보고서의 artifact 열은 예전처럼 실제 파일 이름이다(project 의존성은 class 디렉터리 이름).
                    { assertEquals(listOf("local.jar", "main", "shared.jar"), diagnostics.map { it["artifact"] }.sortedBy(Any?::toString)) },
                    { assertTrue(original.none { text -> locations.any(text::contains) }, original.toString()) },
                )
            }
        }
    }

    @Test
    fun `artifact position inputs change when location keys swap between byte identical artifacts`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        val jar = classJar(root.resolve("jar"), "shared/Shared", "package shared; public class Shared {}")
        val first = root.resolve("libs/a.jar")
        val second = root.resolve("libs/b.jar")
        copy(jar, first)
        copy(jar, second)
        val task = project.tasks.register("dependencyReview", KartographDependenciesTask::class.java).get()
        task.projectDirectory.set(project.layout.projectDirectory)
        task.dependencyArtifacts.from(first, second)
        task.artifactLocations.set(mapOf("{root}/one.jar" to first.toString(), "{root}/two.jar" to second.toString(),
            "{root}/missing.jar" to root.resolve("libs/missing.jar").toString()))
        assertEquals(listOf("{root}/missing.jar\t-1", "{root}/one.jar\t0", "{root}/two.jar\t1"),
            task.inputs.properties["artifactLocationPositions"])
        task.artifactLocations.set(mapOf("{root}/one.jar" to second.toString(), "{root}/two.jar" to first.toString()))
        assertEquals(listOf("{root}/one.jar\t1", "{root}/two.jar\t0"), task.inputs.properties["artifactLocationPositions"])
    }

    @Test
    fun `manual artifact paths and location keys are read only from declared task inputs`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        // 경로 열은 projectDirectory(Gradle이 실제 경로로 바꾼 값) 기준으로 읽으므로 파일도 같은 기준으로 둔다.
        val artifact = project.file("libs/x.jar").toPath()
        copy(classJar(root.resolve("jar"), "shared/Shared", "package shared; public class Shared {}"), artifact)
        val classes = classJar(root.resolve("consumer"), "consumer/Consumer", "package consumer; public class Consumer {}")
        fun review(name: String, column: String, locations: Map<String, String>, tracked: Boolean): KartographDependenciesTask =
            project.tasks.register(name, KartographDependenciesTask::class.java).get().apply {
                projectDirectory.set(project.layout.projectDirectory)
                classRoots.from(classes)
                if (tracked) dependencyArtifacts.from(artifact)
                declarations.set(listOf("example:x:1\timplementation\t$column"))
                artifactLocations.set(locations)
                reportFormat.set("json")
                reportFile.set(project.layout.buildDirectory.file("$name.json"))
            }
        fun reported(task: KartographDependenciesTask): Any? {
            task.analyzeDependencies()
            val document = McpJsonCodec.parse(task.reportFile.get().asFile.readText()) as Map<*, *>
            return ((document["diagnostics"] as List<*>).single() as Map<*, *>)["artifact"]
        }
        // 위치 대응이 없는 수동 설정은 예전처럼 projectDirectory 기준 경로로 읽고 그 경로를 보고한다.
        assertEquals("libs/x.jar", reported(review("pathColumn", "libs/x.jar", emptyMap(), tracked = true)))
        val key = mapOf("{root}/libs/x.jar" to artifact.toString())
        assertEquals("x.jar", reported(review("keyColumn", "{root}/libs/x.jar", key, tracked = true)))
        val untracked = review("untrackedKey", "{root}/libs/x.jar", key, tracked = false)
        assertContains(assertFailsWith<GradleException> { untracked.analyzeDependencies() }.message.orEmpty(),
            "Dependency artifacts must be declared task inputs")
    }

    private fun exercise(root: Path, inProcess: Boolean) {
        val modules = listOf("exported", "transit", "middle", "body", "unused", "testlib", "consumer")
        root.resolve("settings.gradle").writeText("rootProject.name='dependency-fixture'\ninclude " + modules.joinToString(",") { "'$it'" } + "\n")
        root.resolve("build.gradle").writeText("""
            subprojects {
              apply plugin: 'java-library'
              group = 'example'
              version = '1'
              java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
              tasks.withType(Test).configureEach { doFirst { throw new GradleException('analysis must not run tests') } }
            }
        """.trimIndent())
        modules.forEach { Files.createDirectories(root.resolve("$it/src/main/java/lib")) }
        for (name in modules.filter { it != "consumer" }) {
            val type = name.replaceFirstChar(Char::titlecase)
            root.resolve("$name/src/main/java/lib/$type.java").writeText("package lib; public class $type { public static String name() { return \"$name\"; } }")
        }
        root.resolve("middle/build.gradle").writeText("dependencies { api project(':transit') }\n")
        val build = root.resolve("consumer/build.gradle")
        build.writeText("""
            plugins { id 'io.github.ictechgy.kartograph' }
            dependencies {
              implementation project(':exported')
              implementation project(':middle')
              api project(':body')
              implementation project(':unused')
              testImplementation project(':testlib')
            }
            kartograph {
              reportFormat = 'json'
              dependencyIncludeTests = true
              strict = providers.gradleProperty('strictDeps').map { it.toBoolean() }.orElse(false)
            }
        """.trimIndent())
        val source = root.resolve("consumer/src/main/java/lib/Api.java")
        source.writeText("""
            package lib;
            public class Api {
              public java.util.List<Exported> exposed;
              public String run() { return Body.name() + Transit.name(); }
            }
        """.trimIndent())
        val test = Files.createDirectories(root.resolve("consumer/src/test/java/lib"))
        test.resolve("ApiCheck.java").writeText("package lib; public class ApiCheck { public void check() { Testlib.name(); } }")
        fun runner(strict: Boolean = false): GradleRunner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withDebug(inProcess).withArguments(buildList {
                add(":consumer:kartographDependencies"); add("--offline"); add("--stacktrace")
                if (!inProcess) add("--configuration-cache")
                if (strict) add("-PstrictDeps=true")
            })
        val first = runner().build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":consumer:kartographDependencies")!!.outcome)
        assertTrue(first.task(":consumer:compileTestJava") != null)
        assertTrue(first.task(":consumer:test") == null)
        val report = root.resolve("consumer/build/reports/kartograph/jvm-dependencies.txt")
        fun diagnostics(): List<Map<*, *>> = ((McpJsonCodec.parse(report.readText()) as Map<*, *>)["diagnostics"] as List<*>).map { it as Map<*, *> }
        fun finding(module: String, rule: String): Map<*, *> = diagnostics().single { (it["coordinate"] as String).contains(module) && it["ruleId"] == rule }
        assertEquals("api", finding("exported", "dependency-scope-mismatch")["suggestedScope"])
        assertEquals("implementation", finding("body", "dependency-scope-mismatch")["suggestedScope"])
        assertEquals("implementation", finding("transit", "undeclared-dependency")["suggestedScope"])
        assertTrue(diagnostics().none { (it["coordinate"] as String).contains("testlib") })
        assertTrue(diagnostics().any { (it["coordinate"] as String).contains("unused") && it["ruleId"] == "unused-dependency" })
        val again = runner().build()
        if (!inProcess) assertContains(again.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":consumer:kartographDependencies")!!.outcome)
        val strict = runner(strict = true).buildAndFail()
        assertContains(strict.output, "Dependency findings exceeded the strict threshold")
        assertTrue(Files.isRegularFile(report))
        val fingerprints = diagnostics().map { it["fingerprint"] as String }
        val baseline = root.resolve("consumer/dependency-baseline.json")
        baseline.writeText(dev.kartograph.export.BaselineCodec.renderFingerprints(fingerprints))
        val oldBuild = build.readText()
        build.writeText(oldBuild + "\nkartograph { dependencyBaseline = file('dependency-baseline.json') }\n")
        assertEquals(TaskOutcome.SUCCESS, runner(strict = true).build().task(":consumer:kartographDependencies")!!.outcome)
        assertTrue(diagnostics().isEmpty())
        val cachedReview = runner(strict = true).build()
        if (!inProcess) assertContains(cachedReview.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, cachedReview.task(":consumer:kartographDependencies")!!.outcome)
        baseline.writeText(dev.kartograph.export.BaselineCodec.renderFingerprints(emptyList()))
        assertContains(runner(strict = true).buildAndFail().output, "Dependency findings exceeded the strict threshold")
        val suppress = root.resolve("consumer/dependency-suppress.json")
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        fun suppressUntil(date: java.time.LocalDate) = suppress.writeText(dev.kartograph.export.SuppressCodec.render(
            fingerprints.map { dev.kartograph.export.SuppressionEntry(it, "Reviewed fixture", date) }))
        suppressUntil(today)
        build.writeText(oldBuild + "\nkartograph { dependencySuppress = file('dependency-suppress.json') }\n")
        runner(strict = true).build()
        assertTrue(diagnostics().isEmpty())
        suppressUntil(today.minusDays(1))
        assertContains(runner(strict = true).buildAndFail().output, "Dependency findings exceeded the strict threshold")
        build.writeText(oldBuild + "\nkartograph { dependencyBaseline = file('dependency-baseline.json') }\n" +
            "tasks.withType(dev.kartograph.gradle.KartographDependenciesTask).configureEach { baselineOutput = layout.projectDirectory.file('captured.json') }\n")
        assertContains(runner(strict = true).buildAndFail().output, "Dependency findings exceeded the strict threshold")
        assertEquals(fingerprints.toSet(), dev.kartograph.export.BaselineCodec.parse(root.resolve("consumer/captured.json").readText()))
        build.writeText(oldBuild)
        build.writeText(build.readText().replace("implementation project(':exported')", "api project(':exported')"))
        val changedScope = runner().build()
        assertEquals(TaskOutcome.SUCCESS, changedScope.task(":consumer:kartographDependencies")!!.outcome)
        assertFalse(diagnostics().any { (it["coordinate"] as String).contains("exported") })
        source.writeText(source.readText().replace("public java.util.List<Exported>", "private java.util.List<Exported>"))
        val changedClass = runner().build()
        assertEquals(TaskOutcome.SUCCESS, changedClass.task(":consumer:kartographDependencies")!!.outcome)
        assertEquals("implementation", finding("exported", "dependency-scope-mismatch")["suggestedScope"])
    }

    /** class 하나를 컴파일해 entry 시각을 고정한 JAR로 만든다. 여러 위치에 같은 바이트로 복사해 쓴다. */
    private fun classJar(workspace: Path, className: String, source: String): Path {
        val relative = "src/${className.substringAfterLast('/')}.java"
        write(workspace, relative, source)
        val file = workspace.resolve(relative)
        val classes = Files.createDirectories(workspace.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            "-d", classes.toString(), file.toString()), errors.toString())
        val jar = workspace.resolve("artifact.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { archive ->
            archive.putNextEntry(JarEntry("$className.class").apply { time = 0 })
            archive.write(Files.readAllBytes(classes.resolve("$className.class")))
            archive.closeEntry()
        }
        return jar
    }

    private fun copy(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        Files.copy(source, target)
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
