package dev.kartograph.index

import dev.kartograph.core.CompilerReferenceKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class CompilerEvidenceReaderTest {
    private val header = "format\tkartograph-compiler-evidence\t1\ncollector\tjavac-constants\ncompiler\t17.0.20+8\n" +
        "token\t${"a".repeat(64)}\nartifact\t${"b".repeat(64)}\nunmapped\t0\n"

    @Test
    fun `empty results and encoded JVM identities are valid raw facts`() {
        assertEquals(emptyList(), CompilerEvidenceReader.parse(header).references)
        val source = "method:example/사용자#value(Ljava/lang/String;)I"
        val target = "field:example/Constants#VALUE:I"
        val row = "edge\t${encode(source)}\t${encode(target)}\tconstant\n"
        val result = CompilerEvidenceReader.parse(header + "source\t${encode("src/사용자.java")}\t${"c".repeat(64)}\n" + row + row)
        assertEquals("src/사용자.java", result.sources.single().path)
        assertEquals(source, result.references.single().source.value)
        assertEquals(target, result.references.single().target.value)
        assertEquals(CompilerReferenceKind.CONSTANT, result.references.single().kind)
    }

    @Test
    fun `unknown versions duplicate headers negative counts and unsupported rows are rejected`() {
        val invalid = listOf(
            header.replace("evidence\t1", "evidence\t2"),
            header + "token\t${"a".repeat(64)}\n",
            header.replace("unmapped\t0", "unmapped\t-1"),
            header.replace("unmapped\t0", "unmapped\t2147483648"),
            header.replace("unmapped\t0\n", ""),
            header + "unknown\tvalue\n",
            header + "edge\tYQ\tYg\tcall\n",
            header + "source\t${encode("../outside.java")}\t${"c".repeat(64)}\n",
            header + "source\t${encode("/absolute.java")}\t${"c".repeat(64)}\n",
            header + "edge\t_w\tYg\tconstant\n",
            header + "edge\tYQ==\tYg\tconstant\n",
        )
        invalid.forEach { text -> assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parse(text) } }
        val source = "source\t${encode("src/A.java")}\t${"c".repeat(64)}\n"
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parse(header + source + source) }
    }

    @Test
    fun `limits and file encoding fail before partial evidence is returned`(@TempDir root: Path) {
        val file = root.resolve("facts.tsv")
        Files.write(file, byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.read(file) }
        Files.writeString(file, "x".repeat(CompilerEvidenceReader.MAX_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.read(file) }
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceReader.parse(header + "edge\tYQ\tYg\tconstant\n".repeat(CompilerEvidenceReader.MAX_ROWS))
        }
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parse("é".repeat(CompilerEvidenceReader.MAX_BYTES / 2 + 1)) }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
