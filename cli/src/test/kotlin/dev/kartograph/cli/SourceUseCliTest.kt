package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.CompilerCallPositionOptions
import dev.kartograph.index.CompilerEvidenceToken
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SourceUseCliTest {
    private val target = NodeId("method:p/Target#run()V")
    private fun id(name: String) = NodeId("method:p/$name#call()V")

    @Test fun `saved query wrapper retains ordinary values and exact parsed byte identity`(@TempDir directory: Path) {
        val snapshot = captured(listOf("A", "B"))
        for (compact in listOf(false, true)) {
            val canonical = QuerySnapshotCodec.render(snapshot, compact)
            // 유효한 공백과 literal Unicode를 실제 파싱한 bytes에 포함한다.
            val raw = " \n" + canonical.replace("historical", "historical \uE000\uD800\uDC00") + "\t"
            val path = Files.writeString(directory.resolve("$compact.json"), raw)
            val ordinary = run("query", target.value, "--graph-file", "$path", "--limit", "1")
            val wrapped = run("query", target.value, "--graph-file", "$path", "--limit", "1", "--source-uses", "--source-limit", "1")
            assertEquals(0, wrapped.first, wrapped.third)
            val value = json(wrapped.second)
            assertEquals("kartograph-source-use-query", value["format"])
            assertEquals(McpJsonCodec.parse(ordinary.second), value["query"])
            val input = map(map(value["inputs"])["current"])
            assertEquals(hash(raw.toByteArray(Charsets.UTF_8)), input["snapshotSha256"])
            assertEquals("notChecked", input["currentness"])
            val section = map(value["sourceUses"])
            assertEquals("found", section["selectionStatus"])
            val page = map(section["page"])
            assertEquals(2, number(page["observedUses"]))
            assertEquals(1, number(page["returnedUses"]))
            assertEquals(true, page["hasNext"])
            assertEquals(1, list(page["bindings"]).size)
            assertEquals("partial", map(section["coverage"])["coverage"])
            assertEquals(4, number(map(map(map(section["coverage"])["counts"])["producer"])["unmapped"]))
            assertEquals(ordinary, run("query", target.value, "--graph-file", "$path", "--limit", "1"))
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(wrapped.second) }
        }
    }

    @Test fun `unavailable zero captured ambiguous and not found remain distinct`(@TempDir directory: Path) {
        val unavailable = captured(emptyList(), captured = false)
        val empty = captured(emptyList())
        val absent = save(directory, "absent", unavailable)
        val zero = save(directory, "zero", empty)
        val absentSection = source(run("query", target.value, "--graph-file", "$absent", "--source-uses"))
        assertNull(absentSection["page"])
        assertEquals("unavailable", map(absentSection["coverage"])["availability"])
        assertNull(map(absentSection["coverage"])["counts"])
        val zeroSection = source(run("query", target.value, "--graph-file", "$zero", "--source-uses"))
        assertEquals(0, number(map(zeroSection["page"])["observedUses"]))
        assertEquals("captured", map(zeroSection["coverage"])["availability"])
        val multiple = captured(listOf("A", "B"))
        val path = save(directory, "ambiguous", multiple)
        for ((selector, status) in listOf("call" to "ambiguous", "missing" to "notFound")) {
            val ordinary = run("query", selector, "--graph-file", "$path")
            val wrapped = run("query", selector, "--graph-file", "$path", "--source-uses")
            assertEquals(64, wrapped.first)
            assertEquals(McpJsonCodec.parse(ordinary.second), json(wrapped.second)["query"])
            val section = map(json(wrapped.second)["sourceUses"])
            assertEquals(status, section["selectionStatus"])
            assertNull(section["subject"])
            assertNull(section["page"])
            assertEquals("captured", map(section["coverage"])["availability"])
        }
    }

    @Test fun `impact source pages are independent and graph result is unchanged`(@TempDir directory: Path) {
        val base = save(directory, "base", captured(listOf("A", "B", "D"), unmapped = 4))
        val current = save(directory, "current", captured(listOf("X"), unmapped = 0))
        val args = arrayOf("impact", target.value, "--graph-file", "$current", "--base-graph", "$base", "--limit", "1", "--offset", "1")
        val ordinary = run(*args)
        val wrapped = run(*args, "--source-edits", "--source-offset", "1", "--source-limit", "1", "--format", "json")
        assertEquals(0, wrapped.first, wrapped.third)
        val value = json(wrapped.second)
        assertEquals(McpJsonCodec.parse(ordinary.second), value["graphImpact"])
        val edit = map(value["sourceEdit"])
        assertEquals("directObservedSelections", edit["policy"])
        assertEquals("perRevision", edit["pageScope"])
        val revisions = map(edit["revisions"])
        val old = map(revisions["base"]); val now = map(revisions["current"])
        assertEquals("partial", map(old["coverage"])["coverage"])
        assertEquals("unknown", map(now["coverage"])["coverage"])
        val oldPage = map(old["page"]); val nowPage = map(now["page"])
        assertEquals(listOf(3, 3, 1, 1, 1, 1), listOf("observedUses", "observedDistinctCallers", "returnedUses",
            "returnedDistinctCallers", "omittedBefore", "omittedAfter").map { number(oldPage[it]) })
        assertEquals(true, oldPage["hasPrevious"]); assertEquals(true, oldPage["hasNext"])
        assertEquals(id("B").value, map(map(list(oldPage["observations"]).single())["use"])["source"])
        assertEquals(listOf(1, 1, 0, 0, 1, 0), listOf("observedUses", "observedDistinctCallers", "returnedUses",
            "returnedDistinctCallers", "omittedBefore", "omittedAfter").map { number(nowPage[it]) })
        assertEquals(true, nowPage["hasPrevious"]); assertEquals(false, nowPage["hasNext"])
        assertTrue(list(nowPage["observations"]).isEmpty()); assertTrue(list(nowPage["bindings"]).isEmpty())
        assertEquals(ordinary, run(*args))
    }

    @Test fun `unpaged resolved seeds survive graph offset partial selectors and deleted revisions`(@TempDir directory: Path) {
        val full = captured(listOf("A", "B"))
        val current = save(directory, "current", full)
        val partial = run("impact", target.value, "missing", "--graph-file", "$current", "--source-edits", "--offset", "100")
        assertEquals(64, partial.first)
        val value = json(partial.second)
        val section = map(map(map(value["sourceEdit"])["revisions"])["current"])
        assertEquals("partial", section["selectionStatus"])
        assertEquals(listOf(target.value), section["changed"])
        assertEquals(2, number(map(section["page"])["observedUses"]))
        assertEquals(1, list(map(value["graphImpact"])["unresolved"]).size)
        val missing = run("impact", "missing", "--graph-file", "$current", "--source-edits")
        assertEquals(64, missing.first)
        assertNull(impactSection(missing, "current")["page"])
        assertEquals("unresolved", impactSection(missing, "current")["selectionStatus"])
        val emptyFiles = Files.writeString(directory.resolve("files.json"), "[]")
        val noChanges = run("impact", "--files-from", "$emptyFiles", "--graph-file", "$current", "--source-edits")
        assertEquals(0, noChanges.first)
        assertEquals("noChanges", impactSection(noChanges, "current")["selectionStatus"])
        assertEquals(0, number(map(impactSection(noChanges, "current")["page"])["observedUses"]))
        val removed = CodeGraph(emptyList(), emptyList()).preserveCompilerSourceUsesFrom(full.graph)
        val deleted = save(directory, "deleted", full.copy(graph = removed))
        val result = run("impact", target.value, "--graph-file", "$deleted", "--base-graph", "$current", "--source-edits")
        assertEquals(0, result.first, result.third)
        assertEquals("noTargetsInRevision", impactSection(result, "current")["selectionStatus"])
        assertEquals(0, number(map(impactSection(result, "current")["page"])["observedUses"]))
        assertEquals("resolved", impactSection(result, "base")["selectionStatus"])
        assertEquals(2, number(map(impactSection(result, "base")["page"])["observedUses"]))
        val newlyPresent = run("impact", target.value, "--graph-file", "$current", "--base-graph", "$deleted", "--source-edits")
        assertEquals("noTargetsInRevision", impactSection(newlyPresent, "base")["selectionStatus"])
    }

    @Test fun `graph affected callers and source selections never become additional seeds`(@TempDir directory: Path) {
        val full = captured(listOf("A", "B"))
        val evidence = requireNotNull(full.graph.compilerSourceUseEvidence)
        val chain = CodeGraph(full.graph.nodes.values, listOf(GraphEdge(id("A"), target, EdgeKind.CALL)))
            .withCompilerSourceUses(CompilerSourceUseEvidence(evidence.roots, evidence.units, evidence.uses.map { use ->
                if (use.source == id("B")) use.copy(target = id("A")) else use
            }))
        val path = save(directory, "chain", full.copy(graph = chain))
        for (depth in listOf("1", "10")) {
            val args = arrayOf("impact", target.value, "--graph-file", "$path", "--depth", depth)
            val ordinary = run(*args)
            val wrapped = run(*args, "--source-edits")
            assertEquals(0, wrapped.first, wrapped.third)
            assertEquals(McpJsonCodec.parse(ordinary.second), json(wrapped.second)["graphImpact"])
            val section = impactSection(wrapped, "current")
            assertEquals(listOf(target.value), section["changed"])
            val uses = list(map(section["page"])["observations"]).map { map(map(it)["use"]) }
            assertEquals(listOf(id("A").value), uses.map { it["source"] })
            assertEquals(listOf(target.value), uses.map { it["target"] })
        }
    }

    @Test fun `source selection keeps file move retention basename and legacy changed order`(@TempDir directory: Path) {
        val full = captured(listOf("A", "B"))
        val current = save(directory, "current", full)
        val before = full.copy(graph = CodeGraph(full.graph.nodes.values.map { n ->
            if (n.id == target) n.copy(location = SourceLocation("src/old/Target.java", 2, 1)) else n
        }, full.graph.edges).preserveCompilerSourceUsesFrom(full.graph))
        val base = save(directory, "base", before)
        for (file in listOf("src/old/Target.java", "src/Target.java")) {
            val result = run("impact", "--file", file, "--graph-file", "$current", "--base-graph", "$base", "--source-edits")
            assertEquals(0, result.first, result.third)
            assertEquals(listOf(target.value), impactSection(result, "current")["changed"])
            assertEquals(listOf(target.value), impactSection(result, "base")["changed"])
        }
        val basenameGraph = CodeGraph(full.graph.nodes.values.map { n ->
            if (n.id == target) n.copy(location = SourceLocation("Target.java", 2, 1)) else n
        }, full.graph.edges).preserveCompilerSourceUsesFrom(full.graph)
        val basename = save(directory, "basename", full.copy(graph = basenameGraph))
        val selected = run("impact", "--file", "src/Target.java", "--graph-file", "$basename", "--source-edits")
        assertEquals(0, selected.first, selected.third)
        assertEquals(listOf(target.value), impactSection(selected, "current")["changed"])
        assertContains(selected.second, "source-file-candidates:")
        val retained = save(directory, "retained", full.copy(retention = listOf(
            RetentionEvidence(target, RetentionReason.KEEP_RULE, SourceLocation("rules/keep.pro", 1)),
        )))
        val retentionArgs = arrayOf("impact", "--file", "rules/keep.pro", "--graph-file", "$retained")
        val retentionSelection = run(*retentionArgs, "--source-edits")
        assertEquals(0, retentionSelection.first, retentionSelection.third)
        assertEquals(McpJsonCodec.parse(run(*retentionArgs).second), json(retentionSelection.second)["graphImpact"])
        assertEquals(listOf(target.value), impactSection(retentionSelection, "current")["changed"])
        assertEquals(2, number(map(impactSection(retentionSelection, "current")["page"])["observedUses"]))
        val legacyIds = listOf(NodeId("class:p/\uE000"), NodeId("class:p/\uD800\uDC00"))
        val legacy = save(directory, "legacy", QuerySnapshot(CodeGraph(legacyIds.map {
            GraphNode(it, "Same", NodeKind.CLASS)
        }, emptyList()), emptyList(), emptyList()))
        val ordered = run("impact", *legacyIds.map { it.value }.toTypedArray(), "--graph-file", "$legacy", "--source-edits")
        assertEquals(legacyIds.sorted().map { it.value }, impactSection(ordered, "current")["changed"])
    }

    @Test fun `source flags fail usage before live reads and unsupported format dispatch`(@TempDir directory: Path) {
        val path = save(directory, "graph", captured(listOf("A")))
        val query = arrayOf("query", target.value, "--graph-file", "$path")
        val impact = arrayOf("impact", target.value, "--graph-file", "$path")
        for (args in listOf(
            arrayOf("query", target.value, "--classes", "absent", "--project", "$directory", "--source-uses"),
            arrayOf("snapshot", "--classes", "absent", "--project", "$directory", "--include-source-uses"),
            arrayOf("snapshot", "--include-source-uses", "--include-source-uses"),
            arrayOf("snapshot", "merge", "--include-source-uses"),
            query + arrayOf("--source-limit", "1"), impact + arrayOf("--source-offset", "1"),
            query + arrayOf("--source-uses", "--source-uses"), impact + arrayOf("--source-edits", "--source-edits"),
            impact + arrayOf("--source-edits", "--format", "language-traversal"),
            impact + arrayOf("--source-limit", "1", "--format", "language-traversal"),
            query + arrayOf("--source-uses", "--source-limit", "1", "--source-limit", "2"),
            impact + arrayOf("--source-edits", "--source-offset", "0", "--source-offset", "0")
        )) assertEquals(64, run(*args).first, args.joinToString(" "))
        for (flag in listOf("--all", "--sort", "--module", "--affected-file", "--filter-file", "--kind", "--test-status", "--relation", "--path-status")) {
            val suffix = if (flag == "--all") arrayOf(flag) else arrayOf(flag, "invalid")
            assertEquals(64, run(*impact, "--source-edits", *suffix).first, flag)
        }
        for (prefix in listOf(query + "--source-uses", impact + "--source-edits")) {
            for (limit in listOf("0", "1001", "-1", "2147483648", "no"))
                assertEquals(64, run(*prefix, "--source-limit", limit).first, limit)
            for (offset in listOf("-1", "200001", "2147483648", "no"))
                assertEquals(64, run(*prefix, "--source-offset", offset).first, offset)
            for (limit in listOf("1", "100", "1000")) for (offset in listOf("0", "1", "2", "200000"))
                assertEquals(0, run(*prefix, "--source-limit", limit, "--source-offset", offset).first)
        }
    }

    @Test fun `invalid snapshot encodings corrupted extension and oversized output leave stdout empty`(@TempDir directory: Path) {
        val raw = QuerySnapshotCodec.render(captured(listOf("A")))
        val invalid = listOf(("\uFEFF" + raw).toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            raw.replace("\"sourceSha256\": \"" + "d".repeat(64), "\"sourceSha256\": \"bad").toByteArray())
        for ((index, bytes) in invalid.withIndex()) {
            val path = Files.write(directory.resolve("invalid-$index.json"), bytes)
            for (command in listOf("query" to "--source-uses", "impact" to "--source-edits")) {
                val result = run(command.first, target.value, "--graph-file", "$path", command.second)
                assertEquals(2, result.first, result.third)
                assertEquals("", result.second)
            }
        }
        val huge = save(directory, "huge", captured(emptyList()).copy(limitations = listOf("x".repeat(16 * 1024 * 1024))))
        for (command in listOf("query" to "--source-uses", "impact" to "--source-edits")) {
            val result = run(command.first, target.value, "--graph-file", "$huge", command.second)
            assertEquals(2, result.first, result.third)
            assertEquals("", result.second)
            assertContains(result.third, "reduce graph/source pages")
        }
    }

    @Test fun `identity read cannot be rebound by a later file replacement`(@TempDir directory: Path) {
        val path = save(directory, "identity", captured(listOf("A")))
        val before = Files.readAllBytes(path)
        val loaded = SnapshotFiles.readWithIdentity("$path")
        Files.writeString(path, "not a snapshot")
        val result = SavedSnapshotOperations.sourceQuery(loaded, target.value, 1, 50,
            dev.kartograph.analysis.SourceUsePageRequest())
        assertEquals(0, result.status)
        assertEquals(hash(before), map(map(json(result.json)["inputs"])["current"])["snapshotSha256"])
    }

    private fun captured(callers: List<String>, unmapped: Int = 4, captured: Boolean = true): QuerySnapshot {
        val sourceIds = callers.map(::id)
        val classes = InputFingerprint("classes", "build/classes", "a".repeat(64))
        val artifact = "b".repeat(64)
        val receipt = InputFingerprint("compilerEvidence", "build/evidence.tsv", "c".repeat(64))
        val inputs = listOf(InputFingerprint("sources", "src/Calls.java", "d".repeat(64)),
            InputFingerprint("buildConfig", "build.gradle", "e".repeat(64)),
            InputFingerprint("compiler", "compiler/javac", "f".repeat(64)),
            InputFingerprint("processor", "compiler/collector.jar", artifact), CompilerCallPositionOptions.enabledInput())
        val scope = "sample:main"
        val token = CompilerEvidenceToken.create(scope, "javac", ":compileJava", inputs)
        val witness = BuildWitness(scope, "javac", ":compileJava", inputs, listOf(classes), listOf(receipt), token)
        val root = CompilerSourceUseRoot(0, classes, sourceIds + target)
        val unit = CompilerSourceUseUnit(receipt, "javac-constants", "17.0.20+8", artifact, token,
            listOf(CompilerSourceUseClaim(scope, "javac", ":compileJava", listOf(classes))),
            CompilerSourceUseProducerCounts(callers.size + unmapped, callers.size, unmapped, 0),
            CompilerSourceUseImportCounts(callers.size, 0, 0, 0, 0, 0), 0)
        val uses = sourceIds.mapIndexed { index, source -> CompilerSourceUse(unit.id, source, target, root.id, root.id,
            CompilerSourceUseKind.INVOCATION_SELECTION, CompilerEvidenceSource("src/Calls.java", "d".repeat(64)),
            "javac-constants", "17.0.20+8", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
            index * 10, index * 10 + 3, index + 1, 1, false) }
        val nodes = sourceIds.map { GraphNode(it, "call", NodeKind.METHOD, moduleName = "app", location = SourceLocation("src/Calls.java", 1, 1)) } +
            GraphNode(target, "run", NodeKind.METHOD, moduleName = "app", location = SourceLocation("src/Target.java", 2, 1))
        val plain = CodeGraph(nodes, sourceIds.map { GraphEdge(it, target, EdgeKind.CALL) })
        val graph = if (captured) plain.withCompilerSourceUses(CompilerSourceUseEvidence(listOf(root), listOf(unit), uses)) else plain
        return QuerySnapshot(graph, emptyList(), listOf("historical"), scope = scope,
            provenance = SnapshotProvenance(listOf(classes, receipt), listOf(witness)))
    }

    private fun save(directory: Path, name: String, snapshot: QuerySnapshot) =
        Files.writeString(directory.resolve("$name.json"), QuerySnapshotCodec.render(snapshot))
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(out, true, Charsets.UTF_8), PrintStream(error, true, Charsets.UTF_8))
        return Triple(status, out.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun map(value: Any?) = value as Map<*, *>
    private fun list(value: Any?) = value as List<*>
    private fun number(value: Any?) = (value as Number).toInt()
    private fun json(value: String) = map(McpJsonCodec.parse(value))
    private fun source(result: Triple<Int, String, String>): Map<*, *> {
        assertEquals(0, result.first, result.third)
        return map(json(result.second)["sourceUses"])
    }
    private fun impactSection(result: Triple<Int, String, String>, revision: String) =
        map(map(map(json(result.second)["sourceEdit"])["revisions"])[revision])
}
