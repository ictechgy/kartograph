package dev.kartograph.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExternalInputBindingsCodecTest {
    @Test fun `local binding paths round trip with spaces Unicode and platform separators`() {
        val bindings = linkedMapOf("external/compiler-0" to "/opt/tool chain/한글/modules",
            "external/library-1" to "C:\\work area\\library.jar")
        val encoded = ExternalInputBindingsCodec.render(bindings)
        assertEquals(bindings, ExternalInputBindingsCodec.parse(encoded))
        assertEquals(encoded, ExternalInputBindingsCodec.render(bindings.toList().reversed().toMap()))
    }

    @Test fun `invalid documents slots and duplicate keys cannot supply bindings`() {
        assertFailsWith<IllegalArgumentException> { ExternalInputBindingsCodec.parse("{}") }
        assertFailsWith<IllegalArgumentException> { ExternalInputBindingsCodec.render(mapOf("local/path" to "/compiler")) }
        assertFailsWith<IllegalArgumentException> { ExternalInputBindingsCodec.render(mapOf("external/../secret" to "/compiler")) }
        assertFailsWith<IllegalArgumentException> { ExternalInputBindingsCodec.render(mapOf("external/compiler" to "\n")) }
        assertFailsWith<IllegalArgumentException> {
            ExternalInputBindingsCodec.parse("""{"format":"kartograph-local-input-bindings","version":1,"bindings":{"external/x":"/a","external/x":"/b"}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ExternalInputBindingsCodec.parse("""{"format":"kartograph-local-input-bindings","version":1,"bindings":{"external/x":3}}""")
        }
        assertFailsWith<IllegalArgumentException> { ExternalInputBindingsCodec.parse(" ".repeat(1024 * 1024 + 1)) }
    }
}
