package dev.kartograph.export

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContains

class BuildWitnessCodecTest {
    private val witness = BuildWitness("sample:main", "javac", "compileJava",
        listOf("sources", "buildConfig", "compiler", "options").map { InputFingerprint(it, it, "a".repeat(64)) },
        listOf(InputFingerprint("classes", "classes", "b".repeat(64))))

    @Test
    fun `transformation observations use nested provenance v2 independently of snapshot encoding`() {
        val selected = InputFingerprint("classes", "selected", "c".repeat(64))
        val observation = dev.kartograph.core.ClassTransformObservation("sample:main", ":snapshot",
            "hilt-superclass-v1", witness.outputs, listOf(selected),
              listOf(dev.kartograph.core.TransformedClass("p/App", "b".repeat(64), "c".repeat(64))),
              listOf(InputFingerprint("classes", "resources", "d".repeat(64))))
        val provenance = SnapshotProvenance(listOf(selected), listOf(witness), transformations = listOf(observation))
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), provenance = provenance)
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            assertEquals(provenance, QuerySnapshotCodec.parse(text).provenance)
            val parsed = SnapshotJsonParser(text).parse() as Map<*, *>
            assertEquals(if (compact) 2L else 1L, parsed["version"])
            val nested = parsed["provenance"] as Map<*, *>
            assertEquals(2L, nested["version"])
            assertFailsWith<IllegalArgumentException> { BuildWitnessCodec.provenance(nested + ("version" to 1L)) }
            assertFailsWith<IllegalArgumentException> { BuildWitnessCodec.provenance(nested - "transformations") }
            assertEquals(witness, QuerySnapshotCodec.parse(text).provenance!!.witnesses.single())
        }
        val legacy = BuildWitnessCodec.provenanceValue(provenance.copy(transformations = emptyList())) as Map<*, *>
        assertEquals(1, legacy["version"])
        kotlin.test.assertFalse(legacy.containsKey("transformations"))
        assertFailsWith<IllegalArgumentException> { BuildWitnessCodec.provenance(legacy + ("version" to 1L) + ("transformations" to emptyList<Any>())) }
    }

    @Test
    fun `collector receipts require a token and survive both snapshot encodings`() {
        val receipt = InputFingerprint("compilerEvidence", "build/evidence.tsv", "c".repeat(64))
        assertFailsWith<IllegalArgumentException> { witness.copy(compilerEvidence = listOf(receipt)) }
        assertFailsWith<IllegalArgumentException> { witness.copy(evidenceToken = "d".repeat(64)) }
        val recorded = witness.copy(compilerEvidence = listOf(receipt), evidenceToken = "d".repeat(64))
        val text = BuildWitnessCodec.render(recorded)
        assertContains(text, "\"version\": 2")
        assertEquals(recorded, BuildWitnessCodec.parse(text))
        assertFailsWith<IllegalArgumentException> { BuildWitnessCodec.parse(text.replace("\"version\": 2", "\"version\": 1")) }
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(),
            provenance = SnapshotProvenance(emptyList(), listOf(recorded)))
        for (compact in listOf(false, true)) assertEquals(snapshot.provenance,
            QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).provenance)
        assertContains(BuildWitnessCodec.render(witness), "\"version\": 1")
    }

    @Test
    fun `compiler witness and both snapshot encodings round trip without losing input order`() {
        assertEquals(witness, BuildWitnessCodec.parse(BuildWitnessCodec.render(witness)))
        val provenance = SnapshotProvenance(witness.outputs, listOf(witness))
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), provenance = provenance)
        for (compact in listOf(false, true)) assertEquals(provenance, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).provenance)
    }

    @Test
    fun `aggregate member scopes round trip and single captures keep their existing bytes`() {
        val single = SnapshotProvenance(witness.outputs, listOf(witness))
        val singleText = QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), provenance = single))
        kotlin.test.assertFalse(singleText.contains("memberScopes"))
        val aggregate = single.copy(memberScopes = listOf(":b:debug", ":a:debug"))
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), provenance = aggregate)
        for (compact in listOf(false, true)) assertEquals(listOf(":a:debug", ":b:debug"),
            QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).provenance!!.memberScopes)
        assertFailsWith<IllegalArgumentException> { single.copy(memberScopes = listOf(":a:debug", ":a:debug")) }
        assertFailsWith<IllegalArgumentException> { single.copy(memberScopes = listOf("bad scope")) }
    }

    @Test
    fun `malformed witnesses cannot supply paths or partial compiler evidence`() {
        val text = BuildWitnessCodec.render(witness)
        assertFailsWith<IllegalArgumentException> { BuildWitnessCodec.parse(text.replace("\"version\": 1", "\"version\": 9")) }
        assertFailsWith<IllegalArgumentException> { InputFingerprint("sources", "/private/source", "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { InputFingerprint("sources", "../source", "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { witness.copy(inputs = emptyList()) }
    }
}
