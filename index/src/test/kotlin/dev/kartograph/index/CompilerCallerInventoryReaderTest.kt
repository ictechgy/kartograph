package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class CompilerCallerInventoryReaderTest {
    private fun b(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private val id = "method:Calls#zero()V"
    private fun text() = "${CompilerCallerInventoryReader.HEADER}\ncollector\tjavac-constants\ncompiler\t17.0.20\n" +
        "token\t${"a".repeat(64)}\nartifact\t${"b".repeat(64)}\nprofile\t${CompilerCallerInventoryReader.PROFILE}\noutput-root\t${b("classes")}\n" +
        "source\t${b("src/Calls.java")}\t${"c".repeat(64)}\nbody-count\t${b("src/Calls.java")}\t1\n" +
        "body\tb0\t${b("src/Calls.java")}\t0\t100\tORDINARY_METHOD\ttrue\t${b("JAVAC_EXPLICIT")}\nbody-method\tb0\t${b(id)}\n" +
        "output\t${b("Calls.class")}\t${"d".repeat(64)}\t${b("Calls")}\nbackend\t${b(id)}\t9\ttrue\tSOURCE_BOUND\tb0\t${b("JAVAC_EXPLICIT")}\n"
    private fun parse(value: String) = CompilerCallerInventoryReader.parse(value, "e".repeat(64))
    @Test fun `independent inventory records complete source and backend identities`() {
        val result = parse(text())
        assertEquals(id, result.methods.single().id.value)
        assertEquals(CompilerSourceBodyKind.ORDINARY_METHOD, result.bodies.single().kind)
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(text()) }
    }
    @Test fun `zero body and zero method class output remain positive inventory`() {
        val empty = text().lineSequence().filterNot { it.startsWith("body\t") || it.startsWith("body-method\t") || it.startsWith("backend\t") }
            .joinToString("\n").replace("body-count\t${b("src/Calls.java")}\t1", "body-count\t${b("src/Calls.java")}\t0")
        val parsed = parse(empty)
        assertTrue(parsed.bodies.isEmpty()); assertTrue(parsed.methods.isEmpty()); assertEquals(1, parsed.outputs.size)
        assertEquals(mapOf("src/Calls.java" to 0), parsed.sourceBodyCounts)
    }
    @Test fun `malformed duplicate noncanonical or contradictory inventory is rejected`() {
        val source = text()
        val variants = listOf(
            source.replace("inventory\t1", "inventory\t2"), source.replace("accounting-v1", "accounting-v2"),
            source + "unknown\t1\n", source + "output-root\t${b("classes")}\n",
            source + "body-count\t${b("src/Calls.java")}\t1\n", source.replace("\t0\t100\t", "\t00\t100\t"),
            source.replace("\t0\t100\t", "\t-1\t100\t"), source.replace("\t0\t100\t", "\t0\t2147483648\t"),
            source.replace("\ttrue\t", "\tTRUE\t"), source.replace("SOURCE_BOUND", "GENERATED_EXCLUDED"),
            source.replace("SOURCE_BOUND\tb0", "UNKNOWN\t-"), source.replace("SOURCE_BOUND\tb0", "SOURCE_BOUND\tb1"),
            source.replace("${b(id)}\t9", "${b(id)}\t1033"), source.replace(b(id), b("method:Calls#zero(V)V")),
            source.replace(b("Calls.class"), b("Other.class")), source.replace(b("classes"), b("../classes")),
            source.replace(b("JAVAC_EXPLICIT"), b("JAVAC_EXPLICIT") + "="), source.replace(b("JAVAC_EXPLICIT"), "_w"),
            source.replace("body-count\t${b("src/Calls.java")}\t1", "body-count\t${b("src/Calls.java")}\t0"),
            source.replace("compiler\t17.0.20", "compiler\t18.0.1"), source.replace("compiler\t17.0.20", "compiler\t17.invalid version"),
        ) + source.lineSequence().filter { it.startsWith("body\t") || it.startsWith("body-method\t") || it.startsWith("output\t") || it.startsWith("backend\t") }
            .map { source + it + "\n" }.toList()
        variants.forEachIndexed { index, value -> assertFailsWith<IllegalArgumentException>("variant $index") { parse(value) } }
    }
    @Test fun `bounded file reader rejects invalid UTF8 and row byte overflow`(@TempDir root: Path) {
        val path = root.resolve("inventory.tsv")
        Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<IllegalArgumentException> { CompilerCallerInventoryReader.read(path) }
        assertFailsWith<IllegalArgumentException> { parse(text() + "x\n".repeat(CompilerEvidenceReader.MAX_ROWS)) }
        assertFailsWith<IllegalArgumentException> { parse("x".repeat(CompilerEvidenceReader.MAX_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { parse(text().replace(b("JAVAC_EXPLICIT"), "A".repeat(16_385))) }
    }
}
