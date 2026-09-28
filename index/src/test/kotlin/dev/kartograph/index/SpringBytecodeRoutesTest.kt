package dev.kartograph.index

import dev.kartograph.core.RouteParamConstraint
import dev.kartograph.index.fixture.spring.SpringRouteFixtureController
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 바이트코드 원천(class root)으로 route-decl 값을 읽고 소스에서 위치를 얻는 경로를 고정한다.
 * 상수를 소스로 풀 수 없게 둔 표본에서 바이트코드가 접힌 값을 주는지, usr가 snapshot 정점과 같은지 본다.
 */
class SpringBytecodeRoutesTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var outside: Path

    private fun write(root: Path, relative: String, content: String): Path {
        val path = root.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
        return path
    }

    @Test
    fun `kotlin bytecode supplies folded constants and suspend descriptors`() {
        val classes = outside.resolve("classes")
        val packagePath = "dev/kartograph/index/fixture/spring"
        val compiled = Path.of(SpringRouteFixtureController::class.java.protectionDomain.codeSource.location.toURI()).resolve(packagePath)
        classes.resolve(packagePath).createDirectories()
        Files.list(compiled).use { files -> files.forEach { Files.copy(it, classes.resolve(packagePath).resolve(it.fileName)) } }
        val source = Path.of("src/test/kotlin/$packagePath/SpringRouteFixtureController.kt")
        assertTrue(Files.isRegularFile(source), "run from the index module directory so the fixture source is visible")
        Files.createDirectories(project.resolve("src/main/kotlin/$packagePath"))
        Files.copy(source, project.resolve("src/main/kotlin/$packagePath/SpringRouteFixtureController.kt"))
        write(project, "build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"4.1.0\" }\n// spring-boot-starter-webmvc\n")
        val graph = ClassFileIndexer().index(listOf(classes))

        val document = RouteDeclScanner(project, classRoots = listOf(classes)).scan(generatedAt = "2026-01-01T00:00:00Z", graph = graph)
        val facts = document.facts.associateBy { "${it.method} ${it.channel}" }
        assertEquals(setOf("GET /fixture/items/{}", "POST /fixture/items/nested", "PUT /fixture/items/nested"), facts.keys)
        val item = facts.getValue("GET /fixture/items/{}")
        assertEquals("method:$packagePath/SpringRouteFixtureController#item(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;", item.symbol!!.usr)
        assertEquals(listOf(RouteParamConstraint(2, "int")), item.routeDecl!!.paramConstraints)
        assertTrue(item.routeDecl!!.narrowed)
        assertEquals(13, item.location.line)
        assertEquals("strict", item.routeDecl!!.trailingSlash)

        val sourceOnly = RouteDeclScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z")
        assertTrue(sourceOnly.facts.all { it.dynamic }, sourceOnly.facts.toString())
        assertTrue(sourceOnly.limitations.any { it.startsWith("route-coverage: 2 mapping path(s) could not be converted") }, sourceOnly.limitations.toString())
    }

    @Test
    fun `java bytecode resolves overloads, interface mappings and unlocated handlers`() {
        val stubs = listOf(
            "org/springframework/web/bind/annotation/RequestMethod.java" to "package org.springframework.web.bind.annotation; public enum RequestMethod { GET, POST }",
            "org/springframework/web/bind/annotation/RequestMapping.java" to STUB_HEADER +
                "@Retention(RetentionPolicy.RUNTIME) public @interface RequestMapping { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }",
            "org/springframework/web/bind/annotation/GetMapping.java" to STUB_HEADER +
                "@Retention(RetentionPolicy.RUNTIME) public @interface GetMapping { String[] value() default {}; }",
            "org/springframework/web/bind/annotation/PostMapping.java" to STUB_HEADER +
                "@Retention(RetentionPolicy.RUNTIME) public @interface PostMapping { String[] value() default {}; }",
            "org/springframework/web/bind/annotation/RestController.java" to STUB_HEADER +
                "@Retention(RetentionPolicy.RUNTIME) public @interface RestController {}",
            "demo/External.java" to "package demo; public final class External { public static final String BASE = \"/ext\"; }",
            "demo/Hidden.java" to "package demo; import org.springframework.web.bind.annotation.*; @RestController public class Hidden { @GetMapping(\"/hidden\") public String h() { return \"\"; } }",
        ).map { (relative, text) -> write(outside, "stubs/$relative", text) }
        val api = write(project, "src/main/java/demo/Api.java", """
            package demo;
            import org.springframework.web.bind.annotation.GetMapping;
            public interface Api {
                @GetMapping("/iface")
                String iface();
            }
        """)
        val web = write(project, "src/main/java/demo/Web.java", """
            package demo;
            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping(External.BASE)
            public class Web implements Api {
                @GetMapping("/a")
                public String over(String s) {
                    return s;
                }

                @PostMapping("/a")
                public String over(Integer i) {
                    return "";
                }

                public String iface() { return ""; }
            }
        """)
        val classes = outside.resolve("classes").createDirectories()
        val sources = (stubs + listOf(api, web)).map(Path::toString)
        val errors = java.io.ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, *(listOf("-g", "-d", classes.toString()) + sources).toTypedArray()), errors.toString())
        val graph = ClassFileIndexer().index(listOf(classes))

        val document = RouteDeclScanner(project, classRoots = listOf(classes)).scan(generatedAt = "2026-01-01T00:00:00Z", graph = graph)
        val facts = document.facts.associateBy { "${it.method} ${it.channel}" }
        assertEquals(setOf("GET /ext/a", "POST /ext/a", "GET /ext/iface"), facts.keys)
        assertEquals("method:demo/Web#over(Ljava/lang/String;)Ljava/lang/String;", facts.getValue("GET /ext/a").symbol!!.usr)
        assertEquals(7, facts.getValue("GET /ext/a").location.line)
        assertEquals(12, facts.getValue("POST /ext/a").location.line)
        assertEquals("src/main/java/demo/Api.java", facts.getValue("GET /ext/iface").location.path)
        assertEquals("method:demo/Web#iface()Ljava/lang/String;", facts.getValue("GET /ext/iface").symbol!!.usr)
        assertTrue(document.limitations.any { it.startsWith("route-coverage: 1 handler method(s) have no source location") }, document.limitations.toString())
        assertTrue(document.limitations.any { it.startsWith("route-framework-version-unknown:") })
    }

    private companion object {
        const val STUB_HEADER = "package org.springframework.web.bind.annotation; " +
            "import java.lang.annotation.Retention; import java.lang.annotation.RetentionPolicy; "
    }
}
