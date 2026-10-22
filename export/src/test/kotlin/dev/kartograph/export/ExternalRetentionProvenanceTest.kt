package dev.kartograph.export

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.security.MessageDigest

class ExternalRetentionProvenanceTest {
    @Test
    fun `unicode escape tokens require ASCII hexadecimal digits for every envelope version`() {
        for (version in listOf(1, 2)) {
            for (escape in listOf("\\u００００", "\\uＦＦＦＦ", "\\u٠٠٠٠")) {
                for (field in listOf("\"opaque\":\"$escape\"", "\"$escape\":0")) {
                    assertFailsWith<IllegalArgumentException>("version=$version field=$field") {
                        ExternalRetentionCodec.parseDocument(document("""{"version":$version,"limitations":[],$field}"""))
                    }
                }
            }
        }
        // 유효한 ASCII escape와 원본 Unicode 문자열은 동일한 scalar 값으로 계속 읽는다.
        val escaped = ExternalRetentionCodec.parseDocument(document("""{"version":2,"opaque":"\u00e9"}""")).context
        val literal = ExternalRetentionCodec.parseDocument(document("""{"version":2,"opaque":"é"}""")).context
        assertEquals(literal, escaped)
    }

    @Test
    fun `known source and limitation arrays preserve order duplicates and nested scopes`() {
        val source = """{"format":"bridge-facts","version":1,"tool":{"name":"z","version":"1"},
            "platform":"kotlin","project":"/original/é","target":null,"generatedAt":"2024-02-29t00:00:00.123456789012z"}"""
        val limitation = """{"platform":"kotlin","target":null,"tool":"z","message":"e\u0301",
            "origin":"future","channels":["z","a","z"],"routeScope":{"methods":["POST","GET","POST"],"withheldElements":2}}"""
        val value = """{"version":1,"sources":[$source,${source.replace("\"z\"", "\"a\"")},$source],
            "limitations":[$limitation,${limitation.replace("\"tool\":\"z\"", "\"tool\":\"a\"")},$limitation]}"""
        val expected = SnapshotJsonParser(value, strictRetentionRoot = true).parse()
        val parsed = ExternalRetentionCodec.parseDocument(document(value))
        assertEquals(expected, SnapshotJsonParser(parsed.context.provenanceJson!!, strictRetentionRoot = true).parse())
        val snapshot = QuerySnapshot(dev.kartograph.core.CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(),
            externalRetentionContexts = listOf(parsed.context))
        for (compact in listOf(false, true)) {
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).externalRetentionContexts.single()
            assertEquals(expected, SnapshotJsonParser(restored.provenanceJson!!, strictRetentionRoot = true).parse())
        }
        for ((key, row, count) in listOf(Triple("sources", source, 257), Triple("limitations", limitation, 4097))) {
            val over = """{"version":1,"limitations":[],"$key":[${List(count) { row }.joinToString(",")}]}"""
                .replace("\"limitations\":[],\"limitations\":", "\"limitations\":")
            assertFailsWith<IllegalArgumentException> { ExternalRetentionCodec.parseDocument(document(over)) }
        }
    }

    @Test
    fun `missing envelope absent sources empty sources and unknown stay distinct`() {
        val contexts = listOf(fixture("legacy"), document("""{"version":1,"limitations":[]}"""),
            document("""{"version":1,"sources":[],"limitations":[]}"""), document("""{"version":2}"""))
            .map { ExternalRetentionCodec.parseDocument(it).context }
        assertEquals(4, contexts.toSet().size)
        assertEquals(listOf(ExternalRetentionContextStatus.UNAVAILABLE, ExternalRetentionContextStatus.UNAVAILABLE,
            ExternalRetentionContextStatus.UNAVAILABLE, ExternalRetentionContextStatus.UNSUPPORTED), contexts.map { it.status })
        val snapshot = QuerySnapshot(dev.kartograph.core.CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(),
            externalRetentionContexts = contexts)
        for (compact in listOf(false, true)) {
            assertEquals(contexts.toSet(), QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).externalRetentionContexts.toSet())
            val legacy = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot.copy(externalRetentionContexts = emptyList()), compact))
            assertTrue(legacy.externalRetentionContexts.isEmpty())
        }
    }

    @Test
    fun `malformed provenance cannot silently become legacy retention`() {
        for (name in listOf("null-provenance", "string-version", "v2-without-transport")) {
            assertFailsWith<IllegalArgumentException>(name) { ExternalRetentionCodec.parse(fixture(name)) }
        }
    }

    @Test
    fun `unknown versions obey raw token and resource admission before dispatch`() {
        for (name in listOf("unknown-underflow", "unknown-negative-zero", "unknown-surrogate",
            "unknown-version-over", "sources-257", "limitations-4097")) {
            assertFailsWith<IllegalArgumentException>(name) {
                ExternalRetentionCodec.parse(document(fixture(name)))
            }
        }
    }

    @Test
    fun `legacy outer fractional extension and retained identities stay compatible`() {
        val legacy = fixture("legacy")
        assertEquals(ExternalRetentionCodec.parse(legacy),
            ExternalRetentionCodec.parse(legacy.replaceFirst("{", "{\"future\":{\"ratio\":1.5},")))
        assertEquals(ExternalRetentionCodec.parse(legacy).map { it.nodeId },
            ExternalRetentionCodec.parse(fixture("known-stale")).map { it.nodeId })
    }

    @Test
    fun `all frozen portable vectors preserve raw bytes and expected admission`() {
        val manifest = SnapshotJsonParser(checkNotNull(javaClass.getResourceAsStream(
            "/external-retention-provenance-v3/manifest.json")).bufferedReader().use { it.readText() }).parse() as Map<*, *>
        val cases = manifest["cases"] as List<*>
        assertEquals(48, cases.size)
        for (raw in cases) {
            val case = raw as Map<*, *>
            val name = case["id"] as String
            val payload = bytes(name)
            assertEquals(case["sha256"], MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }, name)
            val input = if (case["kind"] == "document") payload else
                (fixture("legacy").trim().dropLast(1) + ",\"provenance\":").toByteArray() + payload + byteArrayOf('}'.code.toByte())
            if ((case["expected"] as String).startsWith("reject")) {
                assertFailsWith<IllegalArgumentException>(name) { ExternalRetentionCodec.parseDocument(input) }
            } else {
                val parsed = ExternalRetentionCodec.parseDocument(input)
                assertEquals(case["canonicalProvenanceUtf8Bytes"], parsed.context.provenanceJson?.toByteArray()?.size?.toLong(), name)
                assertEquals(parsed.context, ExternalRetentionCodec.parseDocument(parsed.context.canonicalDocument).context, name)
                for (compact in listOf(false, true)) {
                    val snapshot = QuerySnapshot(dev.kartograph.core.CodeGraph(emptyList(), emptyList()), emptyList(),
                        emptyList(), externalRetentionContexts = listOf(parsed.context))
                    assertEquals(parsed.context, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact))
                        .externalRetentionContexts.single(), "$name snapshot compact=$compact")
                }
                if (name != "zero-retentions") assertEquals(1, parsed.retentions.size, name)
                if (name.startsWith("timestamp-")) {
                    val original = SnapshotJsonParser(payload.toString(Charsets.UTF_8), strictRetentionRoot = true).parse() as Map<*, *>
                    val captured = SnapshotJsonParser(parsed.context.provenanceJson!!, strictRetentionRoot = true).parse() as Map<*, *>
                    assertEquals(original["sources"], captured["sources"], name)
                }
                when (case["expected"]) {
                    "retain-with-unavailable-context" -> assertEquals(ExternalRetentionContextStatus.UNAVAILABLE, parsed.context.status, name)
                    "retain-with-unsupported-context" -> assertEquals(ExternalRetentionContextStatus.UNSUPPORTED, parsed.context.status, name)
                }
            }
        }
    }

    @Test
    fun `distinct Unicode keys and opaque message controls survive immutable context`() {
        val context = ExternalRetentionCodec.parseDocument(document("""{"version":2,"é":1,"e\u0301":2}""")).context
        assertTrue(context.provenanceJson!!.contains("é"))
        assertTrue(context.provenanceJson!!.contains("e\u0301"))
        assertEquals(context, ExternalRetentionCodec.parseDocument(context.canonicalDocument).context)
        val parsed = ExternalRetentionCodec.parseDocument(fixture("known-stale"))
        assertFailsWith<UnsupportedOperationException> { (parsed.retentions as MutableList).clear() }
        assertTrue(ExternalRetentionCaveats.messages(listOf(parsed.context)).any { it.contains("index-freshness") })
        assertTrue(ExternalRetentionCaveats.messages(listOf(parsed.context)).any { it.contains("not compiler freshness") })
        assertEquals(parsed.context, ExternalRetentionCodec.parseDocument(fixture("zero-retentions")).context)
    }

    @Test
    fun `display is bounded and full opaque provenance survives`() {
        val records = (1..22).joinToString(",") { """{"platform":"x","target":null,"tool":"future","message":"$it\n::error::%<script>"}""" }
        val parsed = ExternalRetentionCodec.parseDocument(document("""{"version":1,"limitations":[$records]}"""))
        val messages = ExternalRetentionCaveats.messages(listOf(parsed.context))
        assertTrue(messages.last().contains("2 additional context records omitted"))
        assertTrue(messages.none { it.contains('\n') || it.contains("<script>") || it.contains('%') })
        assertEquals(22, ((SnapshotJsonParser(parsed.context.provenanceJson!!, strictRetentionRoot = true).parse()
            as Map<*, *>)["limitations"] as List<*>).size)
    }

    @Test
    fun `plain compact snapshots preserve empty and unknown context apart from build proof`() {
        val contexts = listOf("zero-retentions", "unknown-version", "legacy").map {
            ExternalRetentionCodec.parseDocument(fixture(it)).context
        }
        val snapshot = QuerySnapshot(dev.kartograph.core.CodeGraph(emptyList(), emptyList()), emptyList(),
            emptyList(), externalRetentionContexts = contexts)
        for (compact in listOf(false, true)) {
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact))
            assertEquals(contexts.toSet(), restored.externalRetentionContexts.toSet())
            assertEquals(null, restored.provenance)
            assertTrue(restored.retention.isEmpty())
            assertTrue(ExternalRetentionCaveats.messages(restored.externalRetentionContexts).any { it.contains("index-freshness") })
            val without = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot.copy(externalRetentionContexts = emptyList()), compact))
            assertTrue(without.externalRetentionContexts.isEmpty())
        }
    }

    @Test
    fun `snapshot canonical text validates raw tokens and rejects hidden roots`() {
        val snapshot = QuerySnapshot(dev.kartograph.core.CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())
        for (compact in listOf(false, true)) {
            val rendered = QuerySnapshotCodec.render(snapshot, compact).trim().dropLast(1)
            for (invalid in listOf(document("""{"version":2,"bad":1e-400}"""), fixture("known-stale"))) {
                assertFailsWith<IllegalArgumentException> {
                    QuerySnapshotCodec.parse(rendered + ",\"externalRetentionContexts\":[" + jsonValue(invalid) + "]}")
                }
            }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rendered + ",\"externalRetentionContexts\":null}") }
        }
        val nested = """{"version":2,"provenance":${"[".repeat(64)}0${"]".repeat(64)}}"""
        assertFailsWith<IllegalArgumentException> { ExternalRetentionCodec.parseDocument(document(nested)) }
    }

    private fun fixture(name: String): String = bytes(name).toString(Charsets.UTF_8)
    private fun bytes(name: String): ByteArray = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream(
        "/external-retention-provenance-v3/$name.json.gz"))).use { it.readBytes() }
    private fun document(provenance: String): String = fixture("legacy").trim().dropLast(1) +
        ",\"provenance\":$provenance}"
}
