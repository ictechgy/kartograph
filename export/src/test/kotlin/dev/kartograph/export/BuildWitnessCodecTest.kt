package dev.kartograph.export

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildWitnessCodecTest {
    private val witness = BuildWitness("sample:main", "javac", "compileJava",
        listOf("sources", "buildConfig", "compiler", "options").map { InputFingerprint(it, it, "a".repeat(64)) },
        listOf(InputFingerprint("classes", "classes", "b".repeat(64))))

    @Test
    fun `compiler witness and both snapshot encodings round trip without losing input order`() {
        assertEquals(witness, BuildWitnessCodec.parse(BuildWitnessCodec.render(witness)))
        val provenance = SnapshotProvenance(witness.outputs, listOf(witness))
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), provenance = provenance)
        for (compact in listOf(false, true)) assertEquals(provenance, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).provenance)
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
