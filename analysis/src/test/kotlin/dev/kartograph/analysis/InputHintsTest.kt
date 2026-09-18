package dev.kartograph.analysis

import dev.kartograph.core.InputHint
import kotlin.test.Test
import kotlin.test.assertEquals

class InputHintsTest {
    @Test
    fun `reports every missing retention input in a stable order`() {
        assertEquals(
            listOf(InputHint.MISSING_KEEP_RULES, InputHint.MISSING_CLASSPATH, InputHint.MANIFEST_WITHOUT_COMPONENTS),
            InputHints.detect(keepRuleInputs = 0, classpathInputs = 0, manifestEvidenceCount = 0),
        )
    }

    @Test
    fun `supplied inputs and manifest evidence clear their hint`() {
        assertEquals(emptyList(), InputHints.detect(keepRuleInputs = 1, classpathInputs = 2, manifestEvidenceCount = 1))
    }

    @Test
    fun `each condition is independent`() {
        assertEquals(listOf(InputHint.MISSING_CLASSPATH), InputHints.detect(1, 0, 1))
        assertEquals(listOf(InputHint.MANIFEST_WITHOUT_COMPONENTS), InputHints.detect(1, 1, 0))
        assertEquals(listOf(InputHint.MISSING_KEEP_RULES), InputHints.detect(0, 1, 1))
    }
}
