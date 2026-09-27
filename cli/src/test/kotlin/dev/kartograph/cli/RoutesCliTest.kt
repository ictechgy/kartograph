package dev.kartograph.cli

import dev.kartograph.export.McpJsonCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `kartograph routes`의 옵션 검증·종료 코드·결정적 출력 계약이다. */
class RoutesCliTest {
    @TempDir
    lateinit var project: Path

    private data class Execution(val status: Int, val output: String, val error: String)

    private fun execute(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    private fun write(relative: String, content: String): Path {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
        return path
    }

    private fun writeProject() {
        write(
            "app/src/main/kotlin/dev/example/net/Endpoint.kt",
            """
            package dev.example.net
            enum class Verb { GET, POST }
            data class Endpoint(val verb: Verb, val path: String)
            object Endpoints {
                fun list() = Endpoint(Verb.GET, "/api/items")
                fun create() = Endpoint(Verb.POST, "/api/items")
            }
            """,
        )
        write(
            "app/src/test/kotlin/dev/example/net/EndpointTest.kt",
            """
            package dev.example.net
            class EndpointTest { fun fake() = Endpoint(Verb.GET, "/test-only") }
            """,
        )
        write(
            "wrappers.json",
            """
            {"format": "http-wrappers", "version": 1, "wrappers": [
              {"language": "kotlin", "kind": "constructor", "owner": "dev.example.net.Endpoint", "name": "<init>",
               "methodArg": {"index": 0}, "pathArg": {"index": 1}, "methodEnum": {"GET": "GET", "POST": "POST"},
               "pathAnchor": "root", "service": "mobile"},
              {"language": "swift", "kind": "constructor", "owner": "Net.Endpoint", "name": "init",
               "pathArg": {"label": "path"}, "defaultMethod": "GET", "pathAnchor": "root"}
            ]}
            """,
        )
    }

    @Test
    fun `routes emits a client http document with project relative facts`() {
        writeProject()
        val execution = execute("routes", "--role", "client", "--project", project.toString(), "--format", "json", "--wrappers", "wrappers.json")

        assertEquals(ExitStatus.SUCCESS.code, execution.status, execution.error)
        val document = McpJsonCodec.parse(execution.output) as Map<*, *>
        assertEquals("bridge-facts", document["format"])
        assertEquals("kotlin", document["platform"])
        assertEquals("http", document["target"])
        assertEquals(listOf("client"), document["roles"])
        assertEquals(mapOf("tests" to "excluded"), document["sourceSets"])
        val facts = (document["facts"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("GET", "POST"), facts.map { it["method"] })
        assertTrue(facts.all { it["channel"] == "/api/items" && it["pathAnchor"] == "root" && it["service"] == "mobile" })
        assertEquals("app/src/main/kotlin/dev/example/net/Endpoint.kt", (facts.first()["location"] as Map<*, *>)["path"])
        assertFalse(execution.output.contains(project.resolve("app").toString()))
    }

    @Test
    fun `routes output is deterministic apart from the scan time`() {
        writeProject()
        fun run() = execute("routes", "--role", "client", "--project", project.toString(), "--wrappers", "wrappers.json")
            .output.replace(Regex("\"generatedAt\": \"[^\"]+\""), "")
        assertEquals(run(), run())
    }

    @Test
    fun `include tests marks test facts and declares the source set`() {
        writeProject()
        val execution = execute("routes", "--role", "client", "--project", project.toString(), "--wrappers", "wrappers.json", "--include-tests")

        assertEquals(ExitStatus.SUCCESS.code, execution.status, execution.error)
        assertContains(execution.output, "\"sourceSets\": {\"tests\": \"included\"}")
        assertContains(execution.output, "\"testSource\": true")
        assertContains(execution.output, "/test-only")
    }

    @Test
    fun `source roots, service and empty scans are honored`() {
        writeProject()
        write("lib/src/main/kotlin/dev/example/Plain.kt", "class Plain")
        val restricted = execute("routes", "--role", "client", "--project", project.toString(), "--service", "mobile", "lib")

        assertEquals(ExitStatus.SUCCESS.code, restricted.status, restricted.error)
        assertContains(restricted.output, "\"facts\": []")
        assertContains(restricted.output, "\"target\": \"http\"")
        assertContains(restricted.output, "\"service\": \"mobile\"")
    }

    @Test
    fun `usage errors return 64`() {
        writeProject()
        val root = project.toString()
        val cases = listOf(
            listOf("routes", "--project", root) to "missing required --role",
            listOf("routes", "--role", "server", "--project", root) to "not supported yet",
            listOf("routes", "--role", "both", "--project", root) to "invalid routes role",
            listOf("routes", "--role", "client") to "missing required --project",
            listOf("routes", "--role", "client", "--project", root, "--format", "text") to "invalid routes format",
            listOf("routes", "--role", "client", "--project", root, "--unknown") to "unknown option",
            listOf("routes", "--role", "client", "--project") to "missing value",
            listOf("routes", "--role", "client", "--project", root, "--service", " ") to "invalid --service",
            listOf("routes", "--role", "client", "--project", root, project.parent.toString()) to "must be inside --project",
            listOf("routes", "--role", "client", "--project", root, "--wrappers", "wrappers.json", "--service", "other") to "differs from --service",
        )
        cases.forEach { (arguments, message) ->
            val execution = execute(*arguments.toTypedArray())
            assertEquals(ExitStatus.USAGE.code, execution.status, arguments.toString())
            assertContains(execution.error, message)
        }
    }

    @Test
    fun `tool failures return 2 without leaking declaration values`() {
        writeProject()
        write("broken.json", """{"format": "http-wrappers", "version": 1, "wrappers": [{"language": "kotlin", "secretField": "s3cr3t"}]}""")
        val root = project.toString()
        val cases = listOf(
            listOf("routes", "--role", "client", "--project", project.resolve("missing").toString()) to "project root does not exist",
            listOf("routes", "--role", "client", "--project", root, "missing-dir") to "source root does not exist",
            listOf("routes", "--role", "client", "--project", root, "--wrappers", "absent.json") to "does not exist",
            listOf("routes", "--role", "client", "--project", root, "--wrappers", "broken.json") to "unknown field",
            listOf("routes", "--role", "client", "--project", root, "--graph-file", "missing-snapshot.json") to "unable to scan route calls",
        )
        cases.forEach { (arguments, message) ->
            val execution = execute(*arguments.toTypedArray())
            assertEquals(ExitStatus.FAILURE.code, execution.status, arguments.toString())
            assertContains(execution.error, message)
            assertFalse(execution.error.contains("s3cr3t"))
        }
    }

    @Test
    fun `input bindings reconnect snapshot inputs outside the project for freshness`(@TempDir outside: Path) {
        writeProject()
        val source = outside.resolve("Library.java").also { it.writeText("public class Library {}") }
        val classes = outside.resolve("classes").createDirectories()
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val capture = execute("snapshot", "--project", project.toString(), "--classes", classes.toString())
        assertEquals(0, capture.status, capture.error)
        val snapshot = write("snapshot.json", capture.output)
        val slot = dev.kartograph.export.QuerySnapshotCodec.parse(capture.output).provenance!!.inputs.single { it.role == "classes" }.path
        val bindings = write("bindings.json", dev.kartograph.export.ExternalInputBindingsCodec.render(mapOf(slot to classes.toString())))
        val unbound = execute("routes", "--role", "client", "--project", project.toString(), "--graph-file", snapshot.toString())
        assertContains(unbound.output, "graph-file-freshness-unverified: missing-external-input")
        val bound = execute("routes", "--role", "client", "--project", project.toString(), "--graph-file", snapshot.toString(),
            "--input-bindings", bindings.toString())
        assertEquals(0, bound.status, bound.error)
        assertFalse(bound.output.contains("missing-external-input"))
        assertContains(bound.output, "missing-build-witness")
        assertEquals(2, execute("routes", "--role", "client", "--project", project.toString(), "--graph-file", snapshot.toString(),
            "--input-bindings", project.resolve("missing.json").toString()).status)
        assertEquals(64, execute("routes", "--role", "client", "--project", project.toString(), "--input-bindings", bindings.toString()).status)
    }

    @Test
    fun `routes help prints usage`() {
        listOf("--help", "-h").forEach { flag ->
            val execution = execute("routes", flag)
            assertEquals(ExitStatus.SUCCESS.code, execution.status)
            assertContains(execution.output, "kartograph routes --role client")
        }
        assertContains(execute("--help").output, "kartograph routes")
    }
}
