package dev.kartograph.gradle

import dev.kartograph.export.McpJsonCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
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
        build.writeText(build.readText().replace("implementation project(':exported')", "api project(':exported')"))
        val changedScope = runner().build()
        assertEquals(TaskOutcome.SUCCESS, changedScope.task(":consumer:kartographDependencies")!!.outcome)
        assertFalse(diagnostics().any { (it["coordinate"] as String).contains("exported") })
        source.writeText(source.readText().replace("public java.util.List<Exported>", "private java.util.List<Exported>"))
        val changedClass = runner().build()
        assertEquals(TaskOutcome.SUCCESS, changedClass.task(":consumer:kartographDependencies")!!.outcome)
        assertEquals("implementation", finding("exported", "dependency-scope-mismatch")["suggestedScope"])
    }
}
