package dev.kartograph.index

import dev.kartograph.core.*
import java.util.Base64
import kotlin.test.*

class CompilerAuthoredCallReaderTest {
    private fun b(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private val caller = "method:Calls#zero()Ljava/lang/String;"
    private fun text() = "${CompilerAuthoredCallReader.HEADER}\ncollector\tkotlin-constants\ncompiler\t2.4.20\n" +
        "token\t${"a".repeat(64)}\nartifact\t${"b".repeat(64)}\nprofile\tordinary-body-v1\nunmapped\t0\n" +
        "source\t${b("src/Calls.kt")}\t${"c".repeat(64)}\n" +
        "caller\t${b(caller)}\t${b("src/Calls.kt")}\t0\t100\tCOMPLETE\t3\t0\tNONE\n"
    private fun parse(text: String) = CompilerAuthoredCallReader.parse(text, "d".repeat(64))
    @Test fun `complete zero has an explicit independent coverage row`() {
        val row = parse(text()).callers.single()
        assertEquals(caller, row.caller.value)
        assertEquals(AuthoredCallCoverage.COMPLETE, row.coverage)
        assertTrue(row.calls.isEmpty())
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(text()) }
    }
    @Test fun `duplicate malformed unsupported and overflowing evidence is rejected`() {
        val text = text()
        val row = text.lineSequence().first { it.startsWith("caller\t") }
        val invalid = listOf(text + row + "\n", text.replace("\t3\t0\tNONE", "\t2147483648\t0\tNONE"),
            text.replace("\t3\t0\tNONE", "\t3\t1\tNONE"), text.replace("\t0\t100\t", "\t00\t100\t"),
            text.replace("COMPLETE", "INCOMPLETE"), text.replace("2.4.20", "2.4.10"),
            text.replace("ordinary-body-v1", "ordinary-body-v2"), text.replace(b(caller), b(caller) + "="),
            text.replace("\tNONE", "\tUNSUPPORTED_SYNTAX"), text + "unknown\t1\n",
            text.replace("${b("src/Calls.kt")}\t0", "${b("../Calls.kt")}\t0"))
        invalid.forEach { assertFailsWith<IllegalArgumentException> { parse(it) } }
    }
    @Test fun `source-only explicit target can exist without bytecode`() {
        val call = "authored\t${b(caller)}\t${b("method:Value#toString()Ljava/lang/String;")}\tPROJECT\t${b("src/Calls.kt")}\t30\t38\n"
        val parsed = parse(text().replace("\t3\t0\tNONE", "\t3\t1\tNONE") + call)
        assertEquals(1, parsed.callers.single().calls.size)
        assertFailsWith<IllegalArgumentException> { parse(text().replace("\t3\t0\tNONE", "\t3\t2\tNONE") + call + call) }
        val conflict = call.replace(b("method:Value#toString()Ljava/lang/String;"), b("method:Other#toString()Ljava/lang/String;"))
        assertFailsWith<IllegalArgumentException> { parse(text().replace("\t3\t0\tNONE", "\t3\t2\tNONE") + call + conflict) }
    }
}
