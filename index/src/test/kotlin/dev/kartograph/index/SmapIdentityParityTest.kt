package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

/** 위치 객체를 없애더라도 이전 visitor의 원본 줄 근거와 실패 경계를 그대로 보존한다. */
class SmapIdentityParityTest {
    @Test
    fun `identity foreign mappings strides and repeated conflicts retain raw facts`() {
        val entries = listOf(
            "1#1,12:1", "1#1,12:1\n1#1,12:1", "1#1,12:1\n2#1:1\n1#1:1",
            "2#1:1\n1#1:1", "1#1:1\n1#2:1", "1#2:1\n1#1:1",
            "1#1,6:1,2", "3#1,6:1", "65535#1:65535", "0#1,3:0",
            "1#1,9223372036854775808:1", "1#1:1,9223372036854775808",
            "9223372036854775807#1,3:9223372036854775807,9223372036854775807",
        )
        for (source in listOf<String?>(null, "", "Source.kt", "/private/Source.kt", "C:\\private\\Source.kt", "Other.kt")) {
            for (entry in entries) same(source, smap(entry))
        }
        assertEquals(listOf(1), lines(same("Source.kt", smap("1#1:1\n1#1:1"))))
        assertTrue(lines(same("Source.kt", smap("1#1:1\n2#1:1\n1#1:1"))).isEmpty())
        assertEquals(listOf(65535), lines(same("Source.kt", smap("65535#1:65535"))))
    }

    @Test
    fun `strata CRLF unicode and later basename ambiguity keep the same interpretation`() {
        val ordinary = smap("1#1,3:1")
        for (debug in listOf(ordinary, ordinary.replace("\n", "\r\n"),
            ordinary.replace("*S Kotlin\n", ""),
            ordinary.replace("*F", "*S KotlinDebug\n*F\n1 Ignored.kt\n*L\n99#1:1\n*S Kotlin\n*F"),
            ordinary.replace("*E", "*S KotlinDebug\n*F\n1 Ignored.kt\n*L\n99#1:1\n*E"),
            ordinary.replace("Source.kt", "\uD83D\uDE00.kt"),
            ordinary.replace("*E", "*F\n+ 3 Source.kt\nelsewhere/Source.kt\n*L\n1#1:1\n*E"))) {
            same("Source.kt", debug)
            same("\uD83D\uDE00.kt", debug)
        }
        val late = ordinary.replace("*E", "*F\n+ 3 Source.kt\nelsewhere/Source.kt\n*L\n1#1:1\n*E")
        // 이미 저장한 줄은 이후 file section의 basename 충돌로 소급 변경하지 않던 계약이다.
        assertEquals(listOf(1, 2, 3), lines(same("Source.kt", late)))
        val early = ordinary.replace("*L", "*F\n+ 3 Source.kt\nelsewhere/Source.kt\n*L")
        assertTrue(lines(same("Source.kt", early)).isEmpty())
    }

    @Test
    fun `maximum output and mapped-size-before-repeat guards remain unchanged`() {
        assertEquals(listOf(1, 2, 3, 7, 10, 11, 50000, 65535), lines(same("Source.kt", smap("1#1,65535:1"))))
        // 기존 mapped.size + repeat 검사는 중복 구간을 union 하기 전에 적용한다.
        assertTrue(lines(same("Source.kt", smap("1#1,60000:1\n1#1,40001:1"))).isEmpty())
        assertEquals(listOf(1), lines(same("Source.kt", smap("1#1,100000:65536\n1#1:1"))))
        assertTrue(lines(same("Source.kt", smap("1#1,100001:65536\n1#1:1"))).isEmpty())
    }

    @Test
    fun `malformed and truncated SMAP keep unknown or the same exception`() {
        val malformed = listOf("SMAP", "SMAP\nSource.kt\n", "SMAP\nSource.kt\n \n",
            smap("bad"), smap("1#8:1"), smap("1#1,0:1"), smap("1#1:1,0"),
            smap("9223372036854775808#1:1"), smap("1#2147483648:1"),
            smap("1#1:1").replace("*L\n1#1:1\n*E\n", "+ 3 Source.kt"),
            smap("1#1:1").replace("*L", "+ 1 Repeated.kt\nRepeated.kt\n*L"))
        for (debug in malformed) for (source in listOf<String?>(null, "Source.kt")) {
            assertTrue(lines(same(source, debug)).isEmpty())
        }
        val blank = "SMAP\nSource.kt\nKotlin\n*F\n+ 1\n\n*L\n1#1:1\n*E\n"
        for (source in listOf<String?>(null, "Source.kt")) {
            val failure = sameFailure(source, blank)
            assertTrue(failure is IllegalArgumentException)
            assertEquals("source path must not be blank", failure.message)
        }
        // 잘못된 basename이라도 유효한 줄 위치를 만들지 않던 입력은 새로 거부하지 않는다.
        assertTrue(lines(same(null, blank.replace("1#1:1", "0#1:0"))).isEmpty())
    }

    @Test
    fun `UTF8 debug-byte limit remains independent from UTF16 string length`() {
        val below = smap("1#1:1") + "x".repeat(1_048_576 - smap("1#1:1").length)
        assertEquals(listOf(1), lines(same("Source.kt", below)))
        assertTrue(lines(same("Source.kt", below + "x")).isEmpty())
        val multibyte = smap("1#1:1") + "\uD83D\uDE00".repeat(262_144)
        assertTrue(multibyte.length < 1_048_576)
        assertTrue(lines(same("Source.kt", multibyte)).isEmpty())
    }

    private fun lines(facts: ClassFacts): List<Int> = facts.edges.filter { it.kind == EdgeKind.CALL }.flatMap { it.callSiteLines }

    private fun same(source: String?, debug: String): ClassFacts {
        val bytes = caller(source, debug)
        val old = LegacyParseFactsVisitor().also { ClassReader(bytes).accept(it, 0) }.facts()
        val current = FactsVisitor().also { ClassReader(bytes).accept(it, 0) }.facts()
        assertEquals(old, current)
        return current
    }

    private fun sameFailure(source: String?, debug: String): Throwable {
        val bytes = caller(source, debug)
        val old = runCatching { ClassReader(bytes).accept(LegacyParseFactsVisitor(), 0) }.exceptionOrNull()
        val current = runCatching { ClassReader(bytes).accept(FactsVisitor(), 0) }.exceptionOrNull()
        requireNotNull(old)
        requireNotNull(current)
        assertEquals(old.javaClass, current.javaClass)
        assertEquals(old.message, current.message)
        return current
    }

    private fun caller(source: String?, debug: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Calls", null, "java/lang/Object", null)
        visitSource(source, debug)
        for ((index, line) in listOf(0, 1, 2, 3, 7, 10, 11, 50000, 65535).withIndex()) {
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run$index", "()V", null, null).apply {
                visitCode()
                val label = Label()
                visitLabel(label)
                visitLineNumber(line, label)
                visitMethodInsn(Opcodes.INVOKESTATIC, "probe/Target", "hit", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        visitEnd()
    }.toByteArray()

    private fun smap(lines: String): String =
        "SMAP\nSource.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Source.kt\nprobe/Source.kt\n+ 2 Other.kt\nprobe/Other.kt\n*L\n$lines\n*E\n"
}
