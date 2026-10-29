package dev.kartograph.index

import dev.kartograph.core.NodeId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.io.TempDir

class CompilerEvidenceEnvelopeTest {
    private val sourcePath = "src/main/java/demo/Caller.java"
    private val sourceHash = "c".repeat(64)
    private val caller = "method:demo/Caller#use()V"
    private val target = "method:demo/Target#hit()V"
    private val header = "format\tkartograph-compiler-evidence\t3\ncollector\tjavac-constants\ncompiler\t17.0.20+8\n" +
        "token\t${"a".repeat(64)}\nartifact\t${"b".repeat(64)}\nunmapped\t0\n" +
        "source\t${encode(sourcePath)}\t$sourceHash\n"
    private val call = "call\t${encode(caller)}\t${encode(target)}\t${encode(sourcePath)}\t$sourceHash\t90\t93\t5\t21\n"

    @Test
    fun `v3 envelope retains exact stats source coordinates and compiler identity`() {
        val envelope = CompilerEvidenceReader.parseEnvelope(header + "callStats\t1\t1\t0\t0\n" + call)
        assertEquals("javac-constants", envelope.evidence.collector)
        assertEquals("17.0.20+8", envelope.evidence.compilerVersion)
        assertEquals(CompilerCallPositionStats(1, 1, 0, 0), envelope.callStats)
        assertEquals(listOf(UnverifiedCompilerCallPosition(
            NodeId(caller), NodeId(target), envelope.evidence.sources.single(), 90, 93, 5, 21,
        )), envelope.callPositions)
    }

    @Test
    fun `v3 captured empty envelope is distinct from legacy evidence`() {
        val v3 = CompilerEvidenceReader.parseEnvelope(header + "callStats\t0\t0\t0\t0\n")
        assertEquals(CompilerCallPositionStats(0, 0, 0, 0), v3.callStats)
        assertEquals(emptyList(), v3.callPositions)

        val legacyText = header.replace("evidence\t3", "evidence\t1")
        val legacy = CompilerEvidenceReader.parseEnvelope(legacyText)
        assertEquals(null, legacy.callStats)
        assertEquals(emptyList(), legacy.callPositions)
    }

    @Test
    fun `legacy reader explicitly rejects extended evidence instead of discarding rows`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceReader.parse(header + "callStats\t1\t1\t0\t0\n" + call)
        }
        assertContains(failure.message.orEmpty(), "envelope")
    }

    @Test
    fun `v3 rejects invalid stats rows numbers fields collectors and source joins`() {
        val valid = header + "callStats\t1\t1\t0\t0\n" + call
        val invalid = listOf(
            header + call,
            header + "callStats\t1\t1\t0\t0\ncallStats\t1\t1\t0\t0\n" + call,
            valid.replace("callStats\t1\t1\t0\t0", "callStats\t2\t1\t0\t0"),
            valid.replace("callStats\t1\t1\t0\t0", "callStats\t1\t0\t1\t0"),
            valid.replace("callStats\t1\t1\t0\t0", "callStats\t01\t1\t0\t0"),
            valid.replace("callStats\t1\t1\t0\t0", "callStats\t+1\t1\t0\t0"),
            valid.replace("callStats\t1\t1\t0\t0", "callStats\t2147483648\t1\t0\t0"),
            valid.replace("unmapped\t0", "unmapped\t+0"),
            valid.replace("unmapped\t0", "unmapped\t00"),
            valid.replace("\t90\t93\t5\t21", "\t-1\t93\t5\t21"),
            valid.replace("\t90\t93\t5\t21", "\t90\t90\t5\t21"),
            valid.replace("\t90\t93\t5\t21", "\t090\t93\t5\t21"),
            valid.replace("\t90\t93\t5\t21", "\t90\t93\t0\t21"),
            valid.replace("\t90\t93\t5\t21", "\t90\t93\t5"),
            header + "callStats\t1\t1\t0\t0\n" + call.replace(encode(sourcePath), encode("src/main/java/demo/Other.java")),
            header + "callStats\t1\t1\t0\t0\n" + call.replace(sourceHash, "d".repeat(64)),
            valid.replace("javac-constants", "dagger-bindings"),
        )
        invalid.forEach { text -> assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(text) } }
    }

    @Test
    fun `v3 rejects duplicate and conflicting position keys`() {
        val stats = "callStats\t2\t2\t0\t0\n"
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(header + stats + call + call) }
        val conflict = call.replace("\t90\t93\t5\t21", "\t90\t94\t5\t21")
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(header + stats + call + conflict) }
    }

    @Test
    fun `legacy versions reject call rows and stats`() {
        val legacy = header.replace("evidence\t3", "evidence\t1")
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(legacy + "callStats\t0\t0\t0\t0\n") }
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(legacy + call) }
    }

    @Test
    fun `v3 rejects dot path aliases while legacy source identity remains compatible`() {
        val alias = "src/main/java/demo/./Caller.java"
        val v3 = header + "callStats\t1\t1\t0\t0\n" + call
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceReader.parseEnvelope(v3.replace(encode(sourcePath), encode(alias)))
        }
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceReader.parseEnvelope(header + "callStats\t1\t1\t0\t0\n" +
                call.replace(encode(sourcePath), encode(alias)))
        }

        val legacy = header.replace("evidence\t3", "evidence\t1")
            .replace(encode(sourcePath), encode(alias))
        assertEquals(alias, CompilerEvidenceReader.parseEnvelope(legacy).evidence.sources.single().path)
    }

    @Test
    fun `bounded read fingerprints and parses the same captured bytes`(@TempDir root: Path) {
        val original = header + "callStats\t1\t1\t0\t0\n" + call
        val file = root.resolve("evidence.tsv")
        Files.writeString(file, original)

        val captured = BoundedCompilerEvidenceReader.read(file)
        assertEquals(ContentFingerprint.hash(file), captured.fingerprint)
        Files.writeString(file, original.replace("17.0.20+8", "21.0.8+9"))

        assertNotEquals(ContentFingerprint.hash(file), captured.fingerprint)
        assertEquals("17.0.20+8", CompilerEvidenceReader.parseEnvelope(captured.text).evidence.compilerVersion)
    }

    @Test
    fun `v3 rejects an invalid compiler identity even when no calls were emitted`() {
        val empty = header.replace("17.0.20+8", "invalid version") + "callStats\t0\t0\t0\t0\n"
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(empty) }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
