package dev.kartograph.cli

import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEnumDecisionEvidence
import dev.kartograph.core.CompilerEnumDecisionImportCounts
import dev.kartograph.core.CompilerEnumDecisionProducerCounts
import dev.kartograph.core.CompilerEnumDecisionRoot
import dev.kartograph.core.CompilerEnumDecisionSite
import dev.kartograph.core.CompilerEnumDecisionUnit
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.EnumDecisionAttribution
import dev.kartograph.core.EnumDecisionKind
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnumImpactCliTest {
    @Test
    fun `JSON and text are deterministic current source reports with exact input byte hashes`(@TempDir directory: Path) {
        for (compact in listOf(false, true)) {
            val old = fixture(constants = listOf(A), sourcePath = "src/p/Old.kt").copy(limitations = listOf("base-only limitation"))
            val now = fixture().copy(limitations = listOf("current-only limitation"))
            val baseRaw = " \n" + QuerySnapshotCodec.render(old, compact) + "\t"
            val currentRaw = "\n" + QuerySnapshotCodec.render(now, compact) + " "
            val base = Files.writeString(directory.resolve("base-$compact.json"), baseRaw)
            val current = Files.writeString(directory.resolve("current-$compact.json"), currentRaw)
            val arguments = args(current, base)
            val first = run(*arguments, "--format", "json")
            assertEquals(0, first.first, first.third)
            assertEquals(first, run(*arguments, "--format", "json"))
            val value = json(first.second)
            assertEquals("kartograph-enum-expansion", value["format"])
            assertEquals(1, number(value["version"]))
            assertEquals("partial", value["status"])
            val inputs = map(value["inputs"])
            assertEquals(hash(baseRaw.toByteArray(Charsets.UTF_8)), map(inputs["base"])["sha256"])
            assertEquals(hash(currentRaw.toByteArray(Charsets.UTF_8)), map(inputs["current"])["sha256"])
            val comparison = list(value["comparisons"]).map(::map).single { it["enum"] == OWNER.value }
            assertEquals("AVAILABLE", comparison["status"])
            assertEquals(listOf(B.value), comparison["addedConstants"])
            assertEquals(emptyList<String>(), comparison["removedConstants"])
            val candidates = list(value["candidates"]).map(::map)
            assertEquals(2, candidates.size)
            assertEquals(setOf("EXPLICIT_FALLBACK", "FINITE_MEMBERSHIP"), candidates.map { it["kind"] }.toSet())
            candidates.forEach { row ->
                assertEquals("current", row["revision"])
                assertEquals(OWNER.value, row["enum"])
                assertEquals(listOf(B.value), row["addedConstants"])
                assertEquals(listOf(A.value), row["listedConstants"])
                assertEquals("RESOLVED_SAME_OUTPUT_ROOT", row["attribution"])
                assertEquals(row["sourceRootId"], row["enumRootId"])
                assertNotNull(row["evidence"])
                val source = map(row["source"])
                assertEquals("src/p/Use.kt", source["path"])
                assertEquals(SOURCE_HASH, source["sha256"])
                assertTrue(number(source["endOffsetUtf16"]) > number(source["offsetUtf16"]))
                assertEquals("KOTLIN_UTF16_NORMALIZED_SOURCE", source["coordinateBasis"])
                assertEquals(false, source["generated"])
            }
            val unresolved = map(list(value["unresolved"]).single())
            assertEquals("current", unresolved["revision"])
            assertEquals("SOURCE_ROOT_UNRESOLVED", unresolved["reason"])
            assertEquals("src/p/Use.kt", map(unresolved["source"])["path"])
            assertEquals(60, number(map(unresolved["source"])["offsetUtf16"]))
            assertFalse(first.second.contains("src/p/Old.kt"))
            assertContains(first.second, "base-only limitation")
            assertContains(first.second, "current-only limitation")
            val text = run(*arguments, "--format", "text")
            assertEquals(0, text.first, text.third)
            assertEquals(text, run(*arguments, "--format", "text"))
            assertContains(text.second, "src/p/Use.kt")
            assertContains(text.second, SOURCE_HASH)
            assertContains(text.second, "SOURCE_ROOT_UNRESOLVED")
            assertFalse(text.second.contains("src/p/Old.kt"))
            assertContains(text.second, "base-only limitation")
            assertContains(text.second, "current-only limitation")
        }
    }

    @Test
    fun `candidate and unresolved pages keep whole selection counts`(@TempDir directory: Path) {
        val base = save(directory, "base", fixture(constants = listOf(A), captured = false))
        val current = save(directory, "current", fixture(unsupported = 2))
        val full = successful(*args(current, base))
        val page = successful(*args(current, base), "--limit", "1", "--offset", "1")
        assertEquals(1, list(page["candidates"]).size)
        val navigation = map(page["navigation"])
        assertEquals(listOf(1, 1, 2, 1, 1),
            listOf("limit", "offset", "totalCandidates", "returnedCandidates", "omittedCandidates").map { number(navigation[it]) })
        val unresolved = map(page["unresolvedNavigation"])
        assertEquals(1, number(unresolved["totalRecords"]))
        assertEquals(1, number(unresolved["omittedRecords"]))
        assertTrue(list(page["unresolved"]).isEmpty())
        assertEquals(full["coverage"], page["coverage"])
        val coverage = map(page["coverage"])
        assertEquals(true, coverage["evidenceAvailable"])
        assertEquals(3, number(coverage["resolvedSites"]))
        assertEquals(1, number(coverage["unresolvedSites"]))
        assertEquals(0, number(coverage["viewUnavailableSites"]))
        assertEquals(6, number(coverage["producerObserved"]))
        assertEquals(4, number(coverage["producerEmitted"]))
        assertEquals(2, number(coverage["producerUnsupported"]))
        assertEquals(0, number(coverage["producerIgnored"]))
        assertEquals("selectedEnums", coverage["siteCountScope"])
        assertEquals("capturedUnits", coverage["producerCountScope"])
        assertEquals(1, number(coverage["capturedUnits"]))
        val beyond = successful(*args(current, base), "--offset", "200000", "--limit", "1000")
        assertEquals(2, number(map(beyond["navigation"])["totalCandidates"]))
        assertEquals(2, number(map(beyond["navigation"])["omittedCandidates"]))
        assertTrue(list(beyond["candidates"]).isEmpty())
    }

    @Test
    fun `no additions removal only and current explicit coverage do not create candidates`(@TempDir directory: Path) {
        val current = save(directory, "current", fixture(unresolved = false))
        val same = save(directory, "same", fixture(captured = false))
        assertEquals("noCandidates", successful(*args(current, same))["status"])
        val removed = save(directory, "removed", fixture(constants = listOf(A), unresolved = false))
        val removal = successful(*args(removed, same))
        assertEquals("noCandidates", removal["status"])
        val comparison = list(removal["comparisons"]).map(::map).single { it["enum"] == OWNER.value }
        assertEquals(listOf(B.value), comparison["removedConstants"])
        assertTrue(list(removal["candidates"]).isEmpty())
        val before = save(directory, "before", fixture(constants = listOf(A), sourcePath = "src/p/Deleted.kt"))
        val handled = save(directory, "handled", fixture(handled = true, unresolved = false))
        val report = successful(*args(handled, before))
        assertEquals("noCandidates", report["status"])
        assertTrue(list(report["candidates"]).isEmpty())
        assertFalse(report.toString().contains("src/p/Deleted.kt"))
    }

    @Test
    fun `unavailable inspected zero and legacy inventory are distinct`(@TempDir directory: Path) {
        val base = save(directory, "base", fixture(constants = listOf(A), captured = false))
        val absent = save(directory, "absent", fixture(captured = false))
        val unavailable = successful(*args(absent, base))
        assertEquals("evidenceUnavailable", unavailable["status"])
        assertEquals(false, map(unavailable["coverage"])["evidenceAvailable"])
        assertNull(map(unavailable["coverage"])["producerObserved"])
        val zero = save(directory, "zero", fixture(empty = true))
        val inspected = successful(*args(zero, base))
        assertEquals("noCandidates", inspected["status"])
        assertEquals(true, map(inspected["coverage"])["evidenceAvailable"])
        assertEquals(0, number(map(inspected["coverage"])["producerObserved"]))
        val legacyBase = save(directory, "legacy-base", fixture(constants = listOf(A), captured = false).withoutInventory())
        val partial = successful(*args(zero, legacyBase))
        assertEquals("partial", partial["status"])
        assertEquals("BASE_INVENTORY_UNAVAILABLE", list(partial["comparisons"]).map(::map).single { it["enum"] == OWNER.value }["status"])
        val narrowed = save(directory, "narrowed", fixture().withoutInventory())
        val view = successful(*args(narrowed, base))
        assertEquals("partial", view["status"])
        assertEquals(3, number(map(view["coverage"])["viewUnavailableSites"]))
        assertEquals(3, list(view["unresolved"]).map(::map).count { it["reason"] == "VIEW_UNAVAILABLE" })
    }

    @Test
    fun `exact enum selection does not borrow another enum's source sites`(@TempDir directory: Path) {
        val base = save(directory, "base", fixture(constants = listOf(A), captured = false))
        val current = save(directory, "current", fixture())
        val mode = successful(*args(current, base), "--symbol", OWNER.value)
        assertEquals(listOf(OWNER.value), list(mode["comparisons"]).map { map(it)["enum"] })
        assertEquals(2, number(map(mode["navigation"])["totalCandidates"]))
        val other = successful(*args(current, base), "--symbol", OTHER.value)
        assertEquals(listOf(OTHER.value), list(other["comparisons"]).map { map(it)["enum"] })
        assertEquals("noCandidates", other["status"])
        assertEquals(0, number(map(other["coverage"])["resolvedSites"]))
        assertEquals(0, number(map(other["coverage"])["unresolvedSites"]))
        assertEquals(4, number(map(other["coverage"])["producerEmitted"]))
        assertEquals("capturedSites", map(other["coverage"])["attributionCountScope"])
        val captured = map(map(other["coverage"])["capturedAttributionCounts"])
        assertEquals(3, number(captured["RESOLVED_SAME_OUTPUT_ROOT"]))
        assertEquals(1, number(captured["SOURCE_ROOT_UNRESOLVED"]))
        val duplicate = successful(*args(current, base), "--symbol", OWNER.value, "--symbol", OWNER.value)
        assertEquals(mode, duplicate)
    }

    @Test
    fun `incompatible and invalid snapshots fail without partial stdout`(@TempDir directory: Path) {
        val snapshot = fixture()
        val current = save(directory, "current", snapshot)
        for ((name, baseline) in listOf("scope" to fixture(captured = false).copy(scope = "other:main"),
            "tool" to fixture(captured = false).copy(toolVersion = "other"))) {
            val result = run(*args(current, save(directory, name, baseline)))
            assertEquals(2, result.first, result.third)
            assertEquals("", result.second)
        }
        val base = save(directory, "base", fixture(constants = listOf(A), captured = false))
        val raw = QuerySnapshotCodec.render(snapshot)
        assertContains(raw, "\"enumDecisions\": {")
        val malformed = listOf("{}".toByteArray(), ("\uFEFF" + raw).toByteArray(Charsets.UTF_8),
            byteArrayOf(0xc3.toByte(), 0x28),
            raw.replace("\"enumDecisions\": {", "\"enumDecisions\": null, \"discarded\": {").toByteArray(Charsets.UTF_8))
        malformed.forEachIndexed { index, bytes ->
            val invalid = Files.write(directory.resolve("invalid-$index.json"), bytes)
            for (arguments in listOf(args(invalid, base), args(current, invalid))) {
                val result = run(*arguments)
                assertEquals(2, result.first, result.third)
                assertEquals("", result.second)
            }
        }
    }

    @Test
    fun `mode conflicts and duplicate formats fail before reading missing files`(@TempDir directory: Path) {
        val missing = directory.resolve("does-not-exist.json").toString()
        val paths = arrayOf("--graph-file", missing, "--base-graph", missing)
        val flags = listOf(arrayOf("--source-edits"), arrayOf("--format", "language-traversal"),
            arrayOf("--source-limit", "1"), arrayOf("--source-offset", "1"))
        flags.forEach { flag ->
            usage("impact", "--enum-expansion", *flag, *paths)
            usage("impact", *flag, "--enum-expansion", *paths)
        }
        for (first in listOf("json", "text", "language-traversal")) for (second in listOf("json", "text", "language-traversal")) {
            usage("impact", "--enum-expansion", *paths, "--format", first, "--format", second)
            usage("impact", "--format", first, "--enum-expansion", *paths, "--format", second)
            // 과거 recursive json 제거 후 traversal로 빠지던 일반 impact 경로도 같은 경계다.
            usage("impact", OWNER.value, *paths, "--format", first, "--format", second)
        }
    }

    @Test
    fun `missing malformed and duplicate options are usage errors before file reads`(@TempDir directory: Path) {
        val missing = directory.resolve("does-not-exist.json")
        val arguments = args(missing, missing)
        usage("impact", "--enum-expansion", "--graph-file", missing.toString())
        usage("impact", "--enum-expansion", "--base-graph", missing.toString())
        usage(*arguments, OWNER.value)
        usage(*arguments, "--symbol", A.value)
        usage(*arguments, "--symbol", "Mode")
        usage(*arguments, "--format", "csv")
        usage(*arguments, "--enum-expansion")
        for (pair in listOf("--graph-file" to missing.toString(), "--base-graph" to missing.toString(),
            "--limit" to "1", "--offset" to "0")) {
            usage(*arguments, pair.first, pair.second, pair.first, pair.second)
        }
        for (limit in listOf("0", "1001", "-1", "2147483648", "bad")) usage(*arguments, "--limit", limit)
        for (offset in listOf("-1", "200001", "2147483648", "bad")) usage(*arguments, "--offset", offset)
        for (option in listOf("--symbol", "--limit", "--offset", "--format")) usage(*arguments, option)
        usage(*arguments, "--depth", "2")
        usage(*arguments, "--file", "src/p/Use.kt")
    }

    @Test
    fun `help and existing ordinary source and traversal routes retain their single format behavior`(@TempDir directory: Path) {
        for (help in listOf("--help", "-h")) {
            val result = run("impact", "--enum-expansion", help)
            assertEquals(0, result.first, result.third)
            assertContains(result.second, "--enum-expansion")
        }
        val current = save(directory, "current", fixture())
        val base = save(directory, "base", fixture(constants = listOf(A), captured = false))
        val ordinary = arrayOf("impact", OWNER.value, "--graph-file", current.toString(), "--base-graph", base.toString())
        val plain = run(*ordinary)
        assertEquals(0, plain.first, plain.third)
        assertEquals(plain, run(*ordinary, "--format", "json"))
        val source = run(*ordinary, "--source-edits")
        assertEquals(0, source.first, source.third)
        assertEquals(source, run(*ordinary, "--source-edits", "--format", "json"))
        val traversal = listOf(OWNER.value, "--graph-file", current.toString(), "--format", "language-traversal",
            "--project", directory.toString(), "--generated-at", "2026-10-16T00:00:00.000Z")
        val expectedOutput = ByteArrayOutputStream()
        val expectedError = ByteArrayOutputStream()
        val expectedStatus = TraversalCommand.run(traversal, TraversalDirection.DEPENDENTS,
            PrintStream(expectedOutput, true, Charsets.UTF_8), PrintStream(expectedError, true, Charsets.UTF_8))
        assertEquals(0, expectedStatus, expectedError.toString(Charsets.UTF_8))
        assertEquals(Triple(expectedStatus, expectedOutput.toString(Charsets.UTF_8), expectedError.toString(Charsets.UTF_8)),
            run("impact", *traversal.toTypedArray()))
    }

    @Test
    fun `oversized JSON and text reports refuse publication without partial stdout`(@TempDir directory: Path) {
        val normal = save(directory, "normal", fixture())
        val huge = save(directory, "huge", fixture().copy(limitations = listOf("x".repeat(16 * 1024 * 1024))))
        for ((current, base) in listOf(huge to normal, normal to huge)) {
            for (format in listOf("json", "text")) {
                val result = run(*args(current, base), "--format", format)
                assertEquals(2, result.first, result.third)
                assertEquals("", result.second)
                assertTrue(result.third.isNotBlank())
            }
        }
    }

    @Test
    fun `enum capture dependencies and duplicates fail before missing input reads`(@TempDir directory: Path) {
        val missingClasses = directory.resolve("missing-classes").toString()
        val missingSources = directory.resolve("missing-sources").toString()
        val capture = arrayOf("snapshot", "--project", directory.toString(), "--classes", missingClasses,
            "--source-root", missingSources, "--include-enum-decisions")
        val required = listOf("--compiler-evidence" to "missing-evidence.tsv",
            "--scope" to SCOPE, "--build-witness" to "missing-witness.json")
        usage(*capture)
        for (omitted in required.indices) {
            val remaining = required.filterIndexed { index, _ -> index != omitted }.flatMap { listOf(it.first, it.second) }
            usage(*capture, *remaining.toTypedArray())
        }
        usage(*capture, *required.flatMap { listOf(it.first, it.second) }.toTypedArray(), "--include-enum-decisions")
        usage("query", OWNER.value, "--project", directory.toString(), "--classes", missingClasses, "--include-enum-decisions")
        usage("query", OWNER.value, "--graph-file", directory.resolve("missing.json").toString(), "--include-enum-decisions")
    }

    @Test
    fun `snapshot merge refuses captured and inspected empty enum evidence before reindexing`(@TempDir directory: Path) {
        val capturedDirectory = Files.createDirectories(directory.resolve("captured"))
        val plainDirectory = Files.createDirectories(directory.resolve("plain"))
        val plain = fixture(captured = false).copy(scope = "plain:main",
            provenance = SnapshotProvenance(listOf(CLASSES), emptyList()))
        val plainFile = save(plainDirectory, "snapshot", plain)
        for (empty in listOf(false, true)) {
            val capturedFile = save(capturedDirectory, "snapshot-$empty", fixture(empty = empty))
            assertFalse(Files.exists(capturedDirectory.resolve("build/classes")))
            assertFalse(Files.exists(plainDirectory.resolve("build/classes")))
            val result = run("snapshot", "merge", "--project", directory.toString(),
                "--module", "captured", "--graph-file", capturedFile.toString(),
                "--module", "plain", "--graph-file", plainFile.toString())
            assertEquals(2, result.first, result.third)
            assertEquals("", result.second)
            assertContains(result.third, "compiler-evidence witnesses")
        }
    }

    private fun fixture(constants: List<NodeId> = listOf(A, B), captured: Boolean = true, empty: Boolean = false,
        unresolved: Boolean = true, unsupported: Int = 0, handled: Boolean = false, sourcePath: String = "src/p/Use.kt"): QuerySnapshot {
        val inputs = listOf(InputFingerprint("sources", sourcePath, SOURCE_HASH),
            InputFingerprint("buildConfig", "build.gradle.kts", "b".repeat(64)),
            InputFingerprint("compiler", "compiler/kotlin.jar", ARTIFACT),
            InputFingerprint("options", "compiler-enum-decisions", digest(listOf("enumDecisions=true"))))
        val token = digest(listOf("kartograph-compiler-inputs-v1", SCOPE, "kotlin", ":compileKotlin") +
            inputs.flatMap { listOf(it.role, it.path, it.sha256) })
        val root = CompilerEnumDecisionRoot(0, CLASSES, listOf(OWNER, OTHER))
        val emitted = if (empty) 0 else if (unresolved) 4 else 3
        val unit = CompilerEnumDecisionUnit(RECEIPT, "kotlin-constants", "2.4.10", ARTIFACT, token,
            listOf(CompilerSourceUseClaim(SCOPE, "kotlin", ":compileKotlin", listOf(CLASSES))),
            CompilerEnumDecisionProducerCounts(emitted + unsupported, emitted, unsupported, 0),
            CompilerEnumDecisionImportCounts(if (empty) 0 else 3, if (!empty && unresolved) 1 else 0))
        fun site(index: Int, kind: EnumDecisionKind, listed: List<NodeId>, resolved: Boolean = true) =
            CompilerEnumDecisionSite(unit.id, OWNER, listed, kind, null, index == 2,
                CompilerEvidenceSource(sourcePath, SOURCE_HASH), CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
                index * 20, index * 20 + 5, index + 1, 1, false, if (resolved) root.id else null, root.id,
                if (resolved) EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT else EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED)
        val listed = if (handled) constants else listOf(A)
        val sites = if (empty) emptyList() else listOf(
            site(0, EnumDecisionKind.EXPLICIT_FALLBACK, listed),
            site(1, EnumDecisionKind.FINITE_MEMBERSHIP, listed),
            site(2, EnumDecisionKind.NEGATED_FINITE_MEMBERSHIP, constants)) +
            if (unresolved) listOf(site(3, EnumDecisionKind.FINITE_MEMBERSHIP, listed, false)) else emptyList()
        val evidence = if (captured) CompilerEnumDecisionEvidence(listOf(root), listOf(unit), sites) else null
        val witness = BuildWitness(SCOPE, "kotlin", ":compileKotlin", inputs, listOf(CLASSES), listOf(RECEIPT), token)
        return QuerySnapshot(CodeGraph(listOf(GraphNode(OWNER, "Mode", NodeKind.ENUM, enumConstants = constants.sorted()),
            GraphNode(OTHER, "Other", NodeKind.ENUM, enumConstants = listOf(OTHER_A))), emptyList()),
            emptyList(), emptyList(), scope = SCOPE, provenance = SnapshotProvenance(listOf(CLASSES, RECEIPT), listOf(witness)),
            enumDecisionEvidence = evidence)
    }

    private fun QuerySnapshot.withoutInventory(): QuerySnapshot = copy(
        graph = CodeGraph(graph.nodes.values.map { it.copy(enumConstants = null) }, emptyList()))
    private fun args(current: Path, base: Path) = arrayOf("impact", "--enum-expansion", "--graph-file", current.toString(), "--base-graph", base.toString())
    private fun save(directory: Path, name: String, snapshot: QuerySnapshot): Path =
        Files.writeString(directory.resolve("$name.json"), QuerySnapshotCodec.render(snapshot))
    private fun run(vararg arguments: String): Triple<Int, String, String> {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output, true, Charsets.UTF_8), PrintStream(error, true, Charsets.UTF_8))
        return Triple(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }
    private fun usage(vararg arguments: String) {
        val result = run(*arguments)
        assertEquals(64, result.first, arguments.joinToString(" ") + "\n" + result.third)
        assertEquals("", result.second)
    }
    private fun successful(vararg arguments: String): Map<*, *> {
        val result = run(*arguments)
        assertEquals(0, result.first, result.third)
        return json(result.second)
    }
    private fun json(value: String) = map(McpJsonCodec.parse(value))
    private fun map(value: Any?) = value as Map<*, *>
    private fun list(value: Any?) = value as List<*>
    private fun number(value: Any?) = (value as Number).toInt()
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val SCOPE = "sample:main"
        val OWNER = NodeId("class:p/Mode")
        val OTHER = NodeId("class:p/Other")
        val A = NodeId("field:p/Mode#A:Lp/Mode;")
        val B = NodeId("field:p/Mode#B:Lp/Mode;")
        val OTHER_A = NodeId("field:p/Other#A:Lp/Other;")
        val CLASSES = InputFingerprint("classes", "build/classes", "1".repeat(64))
        val RECEIPT = InputFingerprint("compilerEvidence", "build/enum.tsv", "2".repeat(64))
        val ARTIFACT = "3".repeat(64)
        val SOURCE_HASH = "a".repeat(64)
    }
}
