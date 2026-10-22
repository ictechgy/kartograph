package dev.kartograph.export

import dev.kartograph.core.AndroidResourceEvidence
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.AndroidResourceRoot
import dev.kartograph.core.AndroidLayoutDefinition
import dev.kartograph.core.AndroidLayoutInclude
import dev.kartograph.core.AndroidLayoutIncludeKind
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidResourceEvidenceSnapshotTest {
    @Test
    fun `plain and compact preserve qualifiers distinct include sites and unverified status`() {
        val snapshot = fixture()
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            val decoded = QuerySnapshotCodec.parse(text)
            assertEquals(snapshot.androidResourceEvidence, decoded.androidResourceEvidence)
            assertEquals(text, QuerySnapshotCodec.render(decoded, compact))
            val evidence = decoded.androidResourceEvidence!!
            assertEquals("unverified", evidence.admission)
            assertEquals(setOf("", "land"), evidence.definitions.map { it.qualifier }.toSet())
            assertEquals(2, evidence.definitions.map { it.key }.distinct().size)
            assertEquals(5, evidence.includes.size)
            assertEquals(2, evidence.includes.count { it.kind == AndroidLayoutIncludeKind.LITERAL })
            assertEquals(AndroidLayoutIncludeKind.entries.toSet(), evidence.includes.map { it.kind }.toSet())
            assertTrue(decoded.graph.nodeIds.isEmpty())
        }
    }

    @Test
    fun `legacy absence differs from captured empty and explicit null is refused`() {
        val empty = snapshot(AndroidResourceEvidence(SCOPE, NAMESPACE, emptyList(), emptyList(), emptyList()))
        val legacy = QuerySnapshotCodec.render(empty.copy(androidResourceEvidence = null))
        assertFalse("androidResources" in legacy)
        assertNull(QuerySnapshotCodec.parse(legacy).androidResourceEvidence)
        assertEquals(empty.androidResourceEvidence, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(empty)).androidResourceEvidence)
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(legacy) { it["androidResources"] = null }) }
        assertFailsWith<IllegalArgumentException> { empty.copy(scope = "other:debug") }
        assertFailsWith<IllegalArgumentException> { empty.copy(scope = null) }
        assertFailsWith<IllegalArgumentException> { empty.copy(provenance = SnapshotProvenance(emptyList(), emptyList(), listOf(SCOPE))) }
    }

    @Test
    fun `strict extension rejects altered identities shapes kinds ordering and bounds`() {
        val text = QuerySnapshotCodec.render(fixture())
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it["version"] = 2L },
            { it["admission"] = "verified" },
            { it["extra"] = true },
            { obj(obj(rows(it["roots"]).first())["input"])["extra"] = true },
            { obj(obj(rows(it["definitions"]).first())["key"])["extra"] = true },
            { obj(rows(it["roots"]).first())["id"] = "\uD800" },
            { obj(rows(it["roots"]).first())["id"] = "\uDC00" },
            { it.remove("roots") },
            { it["namespace"] = "wrong.namespace" },
            { obj(rows(it["roots"]).first())["id"] = "0".repeat(64) },
            { rows(it["roots"]).add(rows(it["roots"]).first()) },
            { obj(obj(rows(it["roots"]).first())["input"])["path"] = "../outside" },
            { obj(rows(it["definitions"]).first())["sourceSha256"] = "invalid" },
            { obj(rows(it["definitions"]).first())["relativePath"] = "layout/../escape.xml" },
            { obj(rows(it["definitions"]).first())["rootId"] = "0".repeat(64) },
            { rows(it["definitions"]).reverse() },
            { rows(it["includes"]).reverse() },
            { obj(rows(it["includes"]).first())["ordinal"] = 0.5 },
            { obj(rows(it["includes"]).first())["ordinal"] = -1L },
            { obj(rows(it["includes"]).first())["kind"] = "unknown" },
            { rows(it["includes"]).add(rows(it["includes"]).first()) },
            { document -> document["roots"] = List(AndroidResourceEvidence.MAX_ROOTS + 1) { rows(document["roots"]).first() } },
        )
        mutations.forEachIndexed { index, mutation ->
            assertFailsWith<IllegalArgumentException>("mutation $index") {
                QuerySnapshotCodec.parse(rewrite(text) { mutation(obj(it["androidResources"])) })
            }
        }
    }

    @Test
    fun `model rejects cross root files duplicate sites and inconsistent include classification`() {
        val evidence = fixture().androidResourceEvidence!!
        val definition = evidence.definitions.first()
        fun with(definitions: List<AndroidLayoutDefinition> = evidence.definitions,
                 includes: List<AndroidLayoutInclude> = evidence.includes) =
            AndroidResourceEvidence(SCOPE, NAMESPACE, evidence.roots, definitions, includes)
        assertFailsWith<IllegalArgumentException> { with(definitions = listOf(definition.copy(
            file = CompilerEvidenceSource("outside/" + definition.relativePath, HASH)))) }
        assertFailsWith<IllegalArgumentException> { with(definitions = evidence.definitions + definition) }
        assertFailsWith<IllegalArgumentException> { with(includes = listOf(
            AndroidLayoutInclude(definition.id, 0, AndroidLayoutIncludeKind.LITERAL, AndroidResourceKey("other", "layout", "screen")))) }
        assertFailsWith<IllegalArgumentException> { with(includes = listOf(
            AndroidLayoutInclude("0".repeat(64), 0, AndroidLayoutIncludeKind.DYNAMIC))) }
        assertFailsWith<IllegalArgumentException> { with(includes = listOf(
            AndroidLayoutInclude(definition.id, 0, AndroidLayoutIncludeKind.DYNAMIC),
            AndroidLayoutInclude(definition.id, 0, AndroidLayoutIncludeKind.UNSUPPORTED))) }
        assertFailsWith<IllegalArgumentException> { AndroidLayoutInclude(definition.id, 0, AndroidLayoutIncludeKind.LITERAL) }
        assertFailsWith<IllegalArgumentException> { AndroidResourceKey(NAMESPACE, "layout", "../escape") }
        assertFailsWith<IllegalArgumentException> { AndroidResourceRoot(InputFingerprint("sources", "src/main/res", HASH)) }
    }

    @Test
    fun `defensive copies canonicalize input and missing literal targets remain observations`() {
        val evidence = fixture().androidResourceEvidence!!
        val roots = evidence.roots.toMutableList()
        val definitions = evidence.definitions.reversed().toMutableList()
        val includes = evidence.includes.reversed().toMutableList()
        val copy = AndroidResourceEvidence(SCOPE, NAMESPACE, roots, definitions, includes)
        roots.clear(); definitions.clear(); includes.clear()
        assertEquals(evidence, copy)
        assertEquals(evidence.hashCode(), copy.hashCode())
        assertFailsWith<UnsupportedOperationException> { (copy.definitions as MutableList<AndroidLayoutDefinition>).clear() }
        val missing = AndroidLayoutInclude(copy.definitions.first().id, 0, AndroidLayoutIncludeKind.LITERAL,
            AndroidResourceKey(NAMESPACE, "layout", "missing"))
        val unresolved = AndroidResourceEvidence(SCOPE, NAMESPACE, copy.roots, copy.definitions, listOf(missing))
        assertEquals(unresolved, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot(unresolved))).androidResourceEvidence)
    }

    @Test
    fun `non BMP portable root paths roundtrip without changing identity`() {
        val path = "src/\uD83D\uDE80/res"
        val root = AndroidResourceRoot(InputFingerprint("android-resources", path, HASH))
        val definition = AndroidLayoutDefinition(AndroidResourceKey(NAMESPACE, "layout", "screen"), root.id,
            CompilerEvidenceSource("$path/layout/screen.xml", HASH), "layout/screen.xml")
        val evidence = AndroidResourceEvidence(SCOPE, NAMESPACE, listOf(root), listOf(definition), emptyList())
        for (compact in listOf(false, true)) {
            val decoded = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot(evidence), compact))
            assertEquals(evidence, decoded.androidResourceEvidence)
            assertEquals(path, decoded.androidResourceEvidence!!.roots.single().input.path)
        }
    }

    @Test
    fun `graph views preserve observations and content changes change definition identities`() {
        val original = fixture()
        val graph = CodeGraph(listOf(GraphNode(NodeId("class:dev/example/Screen"), "Screen", NodeKind.CLASS)), emptyList())
        val populated = original.copy(graph = graph)
        val pruned = populated.copy(graph = CodeGraph(emptyList(), emptyList()))
        assertEquals(original.androidResourceEvidence, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(pruned)).androidResourceEvidence)
        val definition = original.androidResourceEvidence!!.definitions.first()
        val changed = definition.copy(file = definition.file.copy(sha256 = "b".repeat(64)))
        assertFalse(definition.id == changed.id)
    }

    @Test
    fun `collection and aggregate text limits are enforced without trusting collection size`() {
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", HASH))
        assertFailsWith<IllegalArgumentException> {
            AndroidResourceEvidence(SCOPE, NAMESPACE, generateSequence { root }.asIterable(), emptyList(), emptyList())
        }
        val longRoot = AndroidResourceRoot(InputFingerprint("android-resources", "r".repeat(4000), HASH))
        val definitions = (0 until 2200).map { index ->
            val key = AndroidResourceKey(NAMESPACE, "layout", "screen_$index")
            val relative = "layout/${key.name}.xml"
            AndroidLayoutDefinition(key, longRoot.id, CompilerEvidenceSource(longRoot.input.path + "/" + relative, HASH), relative)
        }
        // 각 행은 root를 포함한 소스 경로를 반복한다. 2,200개는 16 MiB 경계 안이다.
        val accepted = AndroidResourceEvidence(SCOPE, NAMESPACE, listOf(longRoot), definitions, emptyList())
        assertEquals(2200, accepted.definitions.size)
        val more = (2200 until 4400).map { index ->
            val key = AndroidResourceKey(NAMESPACE, "layout", "screen_$index")
            val relative = "layout/${key.name}.xml"
            AndroidLayoutDefinition(key, longRoot.id, CompilerEvidenceSource(longRoot.input.path + "/" + relative, HASH), relative)
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidResourceEvidence(SCOPE, NAMESPACE, listOf(longRoot), definitions + more, emptyList())
        }
    }

    private fun fixture(): QuerySnapshot {
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", HASH))
        val definitions = listOf("screen" to "", "screen" to "land", "child" to "").map { (name, qualifier) ->
            val key = AndroidResourceKey(NAMESPACE, "layout", name)
            val relative = "layout" + if (qualifier.isEmpty()) "/$name.xml" else "-$qualifier/$name.xml"
            AndroidLayoutDefinition(key, root.id, CompilerEvidenceSource("src/main/res/$relative", HASH), relative, qualifier)
        }
        val source = definitions.first().id
        val child = AndroidResourceKey(NAMESPACE, "layout", "child")
        val includes = listOf(
            AndroidLayoutInclude(source, 0, AndroidLayoutIncludeKind.LITERAL, child),
            AndroidLayoutInclude(source, 1, AndroidLayoutIncludeKind.LITERAL, child),
            AndroidLayoutInclude(source, 2, AndroidLayoutIncludeKind.DYNAMIC),
            AndroidLayoutInclude(source, 3, AndroidLayoutIncludeKind.EXTERNAL_NAMESPACE, AndroidResourceKey("android", "layout", "simple_list_item_1")),
            AndroidLayoutInclude(source, 4, AndroidLayoutIncludeKind.UNSUPPORTED),
        )
        return snapshot(AndroidResourceEvidence(SCOPE, NAMESPACE, listOf(root), definitions, includes))
    }
    private fun snapshot(evidence: AndroidResourceEvidence) = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(),
        emptyList(), scope = SCOPE, androidResourceEvidence = evidence)
    private fun rewrite(text: String, mutation: (MutableMap<String, Any?>) -> Unit): String =
        obj(SnapshotJsonParser(text).parse()).also(mutation).let(::jsonValue)
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun rows(value: Any?): MutableList<Any?> = value as MutableList<Any?>
    private companion object {
        const val SCOPE = "app:debug"
        const val NAMESPACE = "dev.example"
        val HASH = "a".repeat(64)
    }
}
