package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.*
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class McpSnapshotTest {
    private fun text(result: Map<*, *>) = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
    private fun snapshot() : QuerySnapshot {
        val nodes = listOf("Target", "Caller", "Caller2", "a/Same", "b/Same").map { name ->
            GraphNode(NodeId("class:$name"), name.substringAfterLast('/'), NodeKind.CLASS,
                location = SourceLocation("src/$name.java"))
        }
        return QuerySnapshot(CodeGraph(nodes, listOf(
            GraphEdge(NodeId("class:Caller"), NodeId("class:Target"), EdgeKind.CALL),
            GraphEdge(NodeId("class:Caller2"), NodeId("class:Caller"), EdgeKind.CALL))), emptyList(),
            listOf("runtime-dispatch: unresolved"), revision = "1".repeat(40), scope = "sample:main")
    }
    private fun write(root: Path, value: QuerySnapshot = snapshot(), name: String = "snapshot.json") =
        Files.writeString(root.resolve(name), QuerySnapshotCodec.render(value))
    private fun client(file: Path, vararg options: String) = McpTestClient { input, output, error ->
        KartographCli.runWithInput(arrayOf("mcp", "--graph-file", file.toString(), *options), output, error, input)
    }
    private fun cli(vararg args: String): Pair<Int, Any?> {
        val output = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(output), PrintStream(error))
        assertEquals("", error.toString())
        return status to McpJsonCodec.parse(output.toString())
    }
    private fun document(result: Map<*, *>): Map<*, *> {
        assertEquals(false, result["isError"], result.toString())
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        assertEquals(McpJsonCodec.parse(text), result["structuredContent"])
        return (result["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
    }

    @Test fun `large recovery suggestions shrink without losing the original missing report`(@TempDir root: Path) {
        val nodes = (1..20).flatMap { owner -> (1..10).map { overload ->
            GraphNode(NodeId("method:p/Writer$owner#value(" + "I".repeat(overload) + ")V"), "value", NodeKind.METHOD,
                location = SourceLocation("src/" + "long-path/".repeat(60) + "Writer$owner.java", overload))
        } }
        val file = write(root, snapshot().copy(graph = CodeGraph(nodes, emptyList())))
        client(file).use { c ->
            c.initialize()
            val requested = (1..20).map { "p.Writer$it.value(double)" }
            val result = c.call("impact", mapOf("symbols" to requested, "limit" to 1, "pathLimit" to 1, "summaryLimit" to 1))
            val actual = document(result)
            assertEquals("notFound", actual["status"])
            assertEquals(20, (actual["unresolved"] as List<*>).size)
            assertTrue(text(result).toByteArray().size <= McpTools.INTERACTIVE_CONTENT_BYTES)
            val wrapper = result["structuredContent"] as Map<*, *>
            val pages = wrapper["suggestions"] as List<*>
            assertEquals(20, pages.size)
            pages.forEach { raw ->
                val page = raw as Map<*, *>
                assertEquals(10L, (page["total"] as Number).toLong())
                assertEquals(true, page["truncated"])
                assertEquals((page["returned"] as Number).toInt(), (page["candidates"] as List<*>).size)
            }
            val response = wrapper["response"] as Map<*, *>
            assertTrue((response["attempts"] as Number).toInt() <= McpTools.MAX_ADAPTATION_ATTEMPTS)
            assertTrue((((response["effective"] as Map<*, *>)["suggestionLimit"]) as Number).toInt() < 10)
        }
    }

    @Test fun `recovery candidates identify current base and shared revisions`(@TempDir root: Path) {
        fun node(name: String, line: Int) = GraphNode(NodeId("method:p/$name#value(I)V"), "value", NodeKind.METHOD,
            location = SourceLocation("src/$name.java", line))
        val current = write(root, snapshot().copy(graph = CodeGraph(listOf(node("Current", 11), node("Shared", 12)), emptyList())))
        val previous = write(root, snapshot().copy(graph = CodeGraph(listOf(node("Deleted", 21), node("Shared", 22)), emptyList())), "base.json")
        client(current, "--base-graph", previous.toString()).use { c ->
            c.initialize()
            val result = c.call("impact", mapOf("symbols" to listOf("p.Current.value(int)", "p.Deleted.value(int)", "p.Shared.value(int)")))
            document(result)
            val pages = ((result["structuredContent"] as Map<*, *>)["suggestions"] as List<*>)
                .associate { raw -> val page = raw as Map<*, *>; page["requested"] to ((page["candidates"] as List<*>).single() as Map<*, *>) }
            assertEquals(listOf("current"), pages.getValue("p.Current.value(int)")["presentIn"])
            assertEquals(listOf("base"), pages.getValue("p.Deleted.value(int)")["presentIn"])
            assertEquals(listOf("current", "base"), pages.getValue("p.Shared.value(int)")["presentIn"])
            assertEquals(12L, ((pages.getValue("p.Shared.value(int)")["location"] as Map<*, *>)["line"] as Number).toLong())
            assertEquals("notFound", document(c.call("query_symbol", mapOf("symbol" to "method:p/Deleted#value(I)V")))["status"])
        }
    }

    @Test fun `source style misses expose exact overload suggestions without changing domain status`(@TempDir root: Path) {
        val overloads = listOf(
            GraphNode(NodeId("method:com/acme/Writer#value(D)V"), "value", NodeKind.METHOD,
                jvmSignature = "com/acme/Writer#value(D)V", location = SourceLocation("src/Writer.java", 10)),
            GraphNode(NodeId("method:com/acme/Writer#value(Ljava/lang/Object;)V"), "value", NodeKind.METHOD,
                jvmSignature = "com/acme/Writer#value(Ljava/lang/Object;)V", location = SourceLocation("src/Writer.java", 11)))
        val file = write(root, snapshot().copy(graph = CodeGraph(overloads, emptyList())))
        client(file).use { c ->
            c.initialize()
            for ((tool, args) in listOf(
                "query_symbol" to mapOf<String, Any?>("symbol" to "com.acme.Writer.value(double)"),
                "impact" to mapOf<String, Any?>("symbols" to listOf("com.acme.Writer.value(double)")))) {
                val result = c.call(tool, args)
                val wrapper = result["structuredContent"] as Map<*, *>
                assertEquals("notFound", (wrapper["document"] as Map<*, *>)["status"])
                val suggestions = wrapper["suggestions"] as List<*>
                val page = suggestions.single() as Map<*, *>
                assertEquals(2, (page["total"] as Number).toInt())
                assertEquals(false, page["truncated"])
                assertEquals(overloads.map { it.id.value }, (page["candidates"] as List<Map<*, *>>).map { it["usr"] })
            }
        }
    }

    @Test fun `interactive results stay within the rendered content budget and record effective limits`(@TempDir root: Path) {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS, location = SourceLocation("src/Target.java"))
        val callers = (1..120).map { index -> GraphNode(NodeId("class:p/Caller$index"), "Caller$index", NodeKind.CLASS,
            location = SourceLocation("src/" + "long".repeat(20) + "/Callers.java")) }
        val graph = CodeGraph(listOf(target) + callers, callers.map { GraphEdge(it.id, target.id, EdgeKind.CALL) })
        val file = write(root, snapshot().copy(graph = graph))
        client(file).use { c ->
            c.initialize()
            for ((tool, args) in listOf(
                "query_symbol" to mapOf<String, Any?>("symbol" to "Target", "depth" to 2, "limit" to 100),
                "impact" to mapOf<String, Any?>("symbols" to listOf("Target"), "limit" to 100, "pathLimit" to 100_000, "summaryLimit" to 100),
                "freshness" to emptyMap())) {
                val result = c.call(tool, args)
                assertEquals(false, result["isError"], result.toString())
                assertTrue(text(result).toByteArray(Charsets.UTF_8).size <= McpTools.INTERACTIVE_CONTENT_BYTES)
                if (tool != "freshness") {
                    val response = (result["structuredContent"] as Map<*, *>)["response"] as Map<*, *>
                    assertEquals(true, response["adapted"])
                    assertTrue((response["attempts"] as Number).toInt() <= McpTools.MAX_ADAPTATION_ATTEMPTS)
                }
            }
        }
    }

    @Test fun `bounded impact summary stays within budget and exactly matches CLI effective limits`(@TempDir root: Path) {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS, location = SourceLocation("src/Target.java"))
        val callers = (1..180).map { index -> GraphNode(NodeId("class:p/Caller$index"), "Caller$index", NodeKind.CLASS,
            moduleName = "module-$index-" + "m".repeat(50), location = SourceLocation("src/" + "p".repeat(60) + "/Caller$index.java")) }
        val file = write(root, snapshot().copy(graph = CodeGraph(listOf(target) + callers,
            callers.map { GraphEdge(it.id, target.id, EdgeKind.CALL) })))
        client(file).use { c ->
            c.initialize()
            val result = c.call("impact", mapOf("symbols" to listOf("Target"), "limit" to 1, "pathLimit" to 1, "summaryLimit" to 100))
            assertEquals(false, result["isError"], result.toString())
            assertTrue(text(result).toByteArray().size <= McpTools.INTERACTIVE_CONTENT_BYTES)
            assertTrue(cli("impact", "Target", "--graph-file", file.toString(), "--depth", "2", "--limit", "1",
                "--path-limit", "1").second.toString().toByteArray().size > McpTools.INTERACTIVE_CONTENT_BYTES)
            val wrapper = result["structuredContent"] as Map<*, *>
            val response = wrapper["response"] as Map<*, *>
            val effective = response["effective"] as Map<*, *>
            assertEquals(100L, ((response["requested"] as Map<*, *>)["summaryLimit"] as Number).toLong())
            val summaryLimit = (effective["summaryLimit"] as Number).toInt()
            assertTrue(summaryLimit <= 5)
            val expected = cli("impact", "Target", "--graph-file", file.toString(), "--depth", "2", "--limit", "1",
                "--path-limit", "1", "--summary-limit", summaryLimit.toString()).second
            assertEquals(expected, wrapper["document"])
            assertEquals(true, (((wrapper["document"] as Map<*, *>)["summaryNavigation"] as Map<*, *>)["truncated"]))
        }
    }

    @Test fun `found missing ambiguous and truncated query documents exactly match CLI`(@TempDir root: Path) {
        val file = write(root)
        client(file).use { c ->
            c.initialize()
            for (symbol in listOf("Target", "Same", "Missing", "class:Caller")) {
                val expected = cli("query", symbol, "--graph-file", file.toString(), "--depth", "2", "--limit", "1").second
                val actual = c.call("query_symbol", mapOf("symbol" to symbol, "depth" to 2, "limit" to 1))
                assertEquals(expected, document(actual))
                val metadata = ((actual["structuredContent"] as Map<*, *>)["snapshot"] as Map<*, *>)["current"] as Map<*, *>
                assertEquals("sample:main", metadata["scope"]); assertEquals("1".repeat(40), metadata["revision"])
                assertEquals(64, (metadata["sha256"] as String).length)
                assertFalse(actual.toString().contains(root.toString()))
            }
        }
    }

    @Test fun `impact pages filters unknowns no changes and base paths exactly match CLI`(@TempDir root: Path) {
        val base = write(root, name = "base.json")
        val file = write(root)
        client(file, "--base-graph", base.toString()).use { c ->
            c.initialize()
            val scenarios = listOf(
                mapOf<String, Any?>("symbols" to listOf("Target"), "limit" to 1, "offset" to 0) to listOf("Target", "--limit", "1", "--offset", "0"),
                mapOf<String, Any?>("symbols" to listOf("Target"), "limit" to 1, "offset" to 1) to listOf("Target", "--limit", "1", "--offset", "1"),
                mapOf<String, Any?>("symbols" to listOf("Missing")) to listOf("Missing", "--limit", "5"),
                mapOf<String, Any?>("symbols" to listOf("Same")) to listOf("Same", "--limit", "5"),
                mapOf<String, Any?>("files" to listOf("src/Target.java"), "affectedFiles" to listOf("src/Caller.java"), "sort" to "usr", "pathLimit" to 1) to
                    listOf("--file", "src/Target.java", "--affected-file", "src/Caller.java", "--sort", "usr", "--path-limit", "1", "--limit", "5"),
                mapOf<String, Any?>("symbols" to listOf("Target"), "visitLimit" to 1, "kinds" to listOf("class"), "testStatus" to "unknown") to
                    listOf("Target", "--visit-limit", "1", "--kind", "class", "--test-status", "unknown", "--limit", "5"))
            for ((args, command) in scenarios) {
                val expected = cli("impact", *command.toTypedArray(), "--graph-file", file.toString(), "--base-graph", base.toString(), "--depth", "2",
                    *if ("--path-limit" in command) emptyArray() else arrayOf("--path-limit", "100"), "--summary-limit", "5").second
                assertEquals(expected, document(c.call("impact", args)))
            }
            assertEquals("noChanges", document(c.call("impact", mapOf("files" to emptyList<String>())))["status"])
        }
        val empty = write(root, QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), scope = "sample:main"), "empty.json")
        client(empty, "--base-graph", base.toString()).use { c ->
            c.initialize()
            val expected = cli("impact", "--file", "src/Target.java", "--graph-file", empty.toString(), "--base-graph", base.toString(), "--depth", "2", "--limit", "5", "--path-limit", "100", "--summary-limit", "5").second
            assertEquals(expected, document(c.call("impact", mapOf("files" to listOf("src/Target.java")))))
        }
    }

    @Test fun `snapshot replacement and deletion cannot change loaded query and impact but freshness rehashes`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("Input.java"), "first")
        val provenance = SnapshotProvenance(listOf(ContentFingerprint.capture(root, source, "sources", "source")), emptyList())
        val file = write(root, snapshot().copy(provenance = provenance))
        client(file, "--project", root.toString()).use { c ->
            c.initialize()
            val before = c.call("query_symbol", mapOf("symbol" to "Target"))
            val impact = c.call("impact", mapOf("symbols" to listOf("Target")))
            assertEquals("unverified", document(c.call("freshness"))["status"])
            Files.writeString(file, "invalid replacement")
            Files.writeString(source, "changed")
            assertEquals(before, c.call("query_symbol", mapOf("symbol" to "Target")))
            val stale = document(c.call("freshness")); assertEquals("stale", stale["status"]); assertContains(stale["reasons"] as List<*>, "changed-sources")
            Files.delete(file)
            assertEquals(impact, c.call("impact", mapOf("symbols" to listOf("Target"))))
        }
    }

    @Test fun `freshness matched stale scope mismatch and no configured project remain domain data`(@TempDir root: Path) {
        fun input(name: String, role: String): InputFingerprint {
            val path = Files.writeString(root.resolve(name), name)
            return ContentFingerprint.capture(root, path, role, name)
        }
        val inputs = listOf(input("source.java", "sources"), input("build.gradle", "buildConfig"), input("compiler.txt", "compiler"),
            InputFingerprint("options", "options", ContentFingerprint.values(listOf("options"))))
        val classes = input("classes.bin", "classes")
        val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(classes))
        val witnessFile = Files.writeString(root.resolve("witness.json"), BuildWitnessCodec.render(witness))
        val provenance = SnapshotProvenance(inputs + classes + ContentFingerprint.capture(root, witnessFile, "witness", "witness"), listOf(witness))
        val file = write(root, snapshot().copy(provenance = provenance))
        client(file, "--project", root.toString(), "--scope", "sample:main").use { c ->
            c.initialize(); val matched = document(c.call("freshness")); assertEquals("matched", matched["status"])
            val expected = cli("verify-snapshot", "--graph-file", file.toString(), "--project", root.toString()).second as Map<*, *>
            assertEquals(expected.filterKeys { it != "hashNanos" }, matched.filterKeys { it != "hashNanos" })
        }
        client(file, "--project", root.toString(), "--scope", "different:main").use { c ->
            c.initialize(); val doc = document(c.call("freshness")); assertEquals("stale", doc["status"]); assertContains(doc["reasons"] as List<*>, "snapshot-scope-mismatch")
        }
        client(file).use { c -> c.initialize(); assertEquals("unverified", document(c.call("freshness"))["status"]) }
    }

    @Test fun `tool schemas enforce types caps selectors enums and no request time file or process options`(@TempDir root: Path) {
        client(write(root)).use { c ->
            c.initialize()
            for (args in listOf(mapOf("symbol" to "Target", "depth" to 0), mapOf("symbol" to "Target", "limit" to 101),
                mapOf("symbol" to "Target", "depth" to 1.5), mapOf("symbol" to "x".repeat(2049)), mapOf("symbol" to "Target", "graphFile" to "/forbidden"),
                mapOf("symbol" to true), mapOf("symbol" to "Target", "snapshotMaxMiB" to 128),
                emptyMap<String, Any?>())) assertEquals(true, c.call("query_symbol", args)["isError"])
            for (args in listOf(mapOf("symbols" to List(21) { "Target" }), mapOf("files" to listOf("../forbidden")), mapOf("files" to listOf("/forbidden")),
                mapOf("symbols" to listOf("Target"), "sort" to "invalid"), mapOf("symbols" to listOf("Target"), "offset" to -1),
                mapOf("symbols" to listOf("Target"), "visitLimit" to 100001), mapOf("symbols" to listOf("Target"), "pathLimit" to 0),
                mapOf("symbols" to listOf("Target"), "all" to true), mapOf("filesFrom" to "/forbidden"),
                mapOf("snapshotMaxMiB" to 128), mapOf("command" to "touch /forbidden")))
                assertEquals(true, c.call("impact", args)["isError"])
            for (name in listOf("project", "input", "inputBindings", "graphFile", "snapshotMaxMiB"))
                assertEquals(true, c.call("freshness", mapOf(name to "/forbidden"))["isError"])
            assertEquals("found", document(c.call("query_symbol", mapOf("symbol" to "Target")))["status"])
        }
    }

    @Test fun `large mandatory query data returns actionable error instead of truncating JSON`(@TempDir root: Path) {
        val file = write(root, snapshot().copy(limitations = listOf("measured-limit: " + "x".repeat(McpServer.MAX_RESPONSE))))
        client(file).use { c ->
            c.initialize()
            val result = c.call("query_symbol", mapOf("symbol" to "Target"))
            assertEquals(true, result["isError"])
            assertFalse(result.containsKey("structuredContent"))
            assertContains(result.toString(), "smaller")
            assertEquals("unverified", document(c.call("freshness"))["status"])
        }
    }

    @Test fun `startup external bindings stay fixed after binding file replacement`(@TempDir root: Path) {
        val project = Files.createDirectory(root.resolve("project"))
        val externalFile = Files.writeString(root.resolve("external.bin"), "first")
        val fingerprint = ContentFingerprint.capture(project, externalFile, "classes", "classes")
        val file = write(project, snapshot().copy(provenance = SnapshotProvenance(listOf(fingerprint), emptyList())))
        val bindings = Files.writeString(root.resolve("bindings.json"), ExternalInputBindingsCodec.render(mapOf(fingerprint.path to externalFile.toString())))
        client(file, "--project", project.toString(), "--input-bindings", bindings.toString()).use { c ->
            c.initialize()
            assertFalse(document(c.call("freshness")).toString().contains("missing-external-input"))
            Files.writeString(bindings, "invalid replacement")
            Files.writeString(externalFile, "changed")
            assertEquals("stale", document(c.call("freshness"))["status"])
        }
        client(file, "--project", project.toString(), "--input", "${fingerprint.path}=$externalFile").use { c ->
            c.initialize(); assertEquals("stale", document(c.call("freshness"))["status"])
        }
    }

    @Test fun `startup rejects mismatched base malformed inputs and duplicate options without leaking paths`(@TempDir root: Path) {
        val file = write(root)
        val other = write(root, snapshot().copy(scope = "other:main"), "other.json")
        for (args in listOf(listOf("--base-graph", other.toString()), listOf("--project", root.resolve("absent").toString()),
            listOf("--input-bindings", file.toString()))) {
            client(file, *args.toTypedArray()).use { c -> c.close(); assertEquals(2, c.status); assertFalse(c.errors.toString().contains(root.toString())) }
        }
        val output = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        assertEquals(64, KartographCli.run(arrayOf("mcp", "--graph-file", file.toString(), "--graph-file", file.toString()), PrintStream(output), PrintStream(error)))
    }
}
