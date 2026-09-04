package dev.kartograph.export

import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextLocationTest {
    @Test
    fun `renders absent file line and column locations`() {
        assertEquals("-", null.toPlainTextLocation())
        assertEquals("Source.kt", SourceLocation("Source.kt").toPlainTextLocation())
        assertEquals("Source.kt:7", SourceLocation("Source.kt", 7).toPlainTextLocation())
        assertEquals("Source.kt:7:3", SourceLocation("Source.kt", 7, 3).toPlainTextLocation())
    }
}
