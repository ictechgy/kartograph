package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class KeepRuleFeedbackTest {
    @Test fun `dontshrink remains an explicit global retention directive`(@TempDir root: Path) {
        val file = root.resolve("keep.pro").also { it.writeText("-dontshrink\n-dontoptimize\n") }
        val rule = KeepRuleScanner(root).scan(listOf(file)).single()
        assertTrue(rule.shrinkingDisabled)
        assertFalse(rule.unresolved)
        assertEquals(SourceLocation("keep.pro", 1), rule.location)
    }

    @Test fun `concrete member modifiers and wildcard fields preserve constraints`(@TempDir root: Path) {
        val file = root.resolve("keep.pro").also { it.writeText("""
            -keep class Example { public static final java.lang.String NAME; !private static *** run(...); *** value; }
        """.trimIndent()) }
        val members = KeepRuleScanner(root).scan(listOf(file)).single().keptMembers
        assertEquals(setOf(JvmModifier.STATIC, JvmModifier.FINAL), members[0].requiredJvmModifiers)
        assertEquals(setOf(Visibility.PUBLIC), members[0].requiredJvmVisibilities)
        assertEquals("Ljava/lang/String;", members[0].jvmDescriptor)
        assertEquals(setOf(Visibility.PRIVATE), members[1].forbiddenJvmVisibilities)
        assertEquals("value", members[2].namePattern)
        assertNull(members[2].jvmDescriptor)
    }

    @Test fun `strict aggregates while lenient retains unresolved independent rules`(@TempDir root: Path) {
        val file = root.resolve("keep.pro").also { it.writeText("-not-supported\n-keep,includecode class Example\n-keep class Known\n") }
        val error = assertFailsWith<KeepRuleScanningException> { KeepRuleScanner(root).scan(listOf(file)) }
        assertContains(error.message!!, "keep.pro:1")
        assertContains(error.message!!, "keep.pro:2")
        val scanner = KeepRuleScanner(root, lenient = true)
        val rules = scanner.scan(listOf(file))
        assertEquals(2, scanner.diagnostics.size)
        assertEquals(2, rules.count { it.unresolved })
        assertEquals("Known", rules.single { !it.unresolved }.classNamePattern)
        file.writeText("-keep class Example { nonsense;\n}\n-keep class Known\n")
        assertEquals("Known", scanner.scan(listOf(file)).last().classNamePattern)
    }
}
