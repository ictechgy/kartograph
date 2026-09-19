package dev.kartograph.export

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DependencyListCodecTest {
    @Test
    fun `parse accepts comments blank lines CRLF and absolute artifacts`() {
        val content = "# declared for the debug variant\n\n" +
            "com.example:lib:1.0\timplementation\tlibs/lib.jar\r\n" +
            "androidx.core:core:1.0\tapi\t/opt/libs/core.jar\n"

        assertEquals(
            listOf(
                DeclaredDependency("com.example:lib:1.0", DependencyScope.IMPLEMENTATION, "libs/lib.jar"),
                DeclaredDependency("androidx.core:core:1.0", DependencyScope.API, "/opt/libs/core.jar"),
            ),
            DependencyListCodec.parse(content),
        )
    }

    @Test
    fun `parse rejects malformed columns blank fields and unknown scopes`() {
        assertContains(
            assertFailsWith<IllegalArgumentException> { DependencyListCodec.parse("only-one-column\n") }
                .message.orEmpty(),
            "malformed line at line 1",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { DependencyListCodec.parse("\timplementation\tlibs/lib.jar\n") }
                .message.orEmpty(),
            "invalid coordinate at line 1",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { DependencyListCodec.parse("a:b:1\timplementation\t\n") }
                .message.orEmpty(),
            "blank artifact at line 1",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { DependencyListCodec.parse("a:b:1\tbogus\tlibs/lib.jar\n") }
                .message.orEmpty(),
            "unknown scope at line 1",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { DependencyListCodec.parse("a b:1\timplementation\tlibs/lib.jar\n") }
                .message.orEmpty(),
            "invalid coordinate at line 1",
        )
    }

    @Test
    fun `parse deduplicates exact entries and render sorts deterministically`() {
        val dependency = DeclaredDependency("com.example:lib:1.0", DependencyScope.IMPLEMENTATION, "libs/lib.jar")

        val rendered = DependencyListCodec.render(listOf(dependency))
        assertEquals(listOf(dependency), DependencyListCodec.parse(rendered))
        assertEquals(listOf(dependency), DependencyListCodec.parse(rendered + rendered))
        assertEquals(
            "a:b:1\tapi\tb.jar\na:b:1\timplementation\ta.jar\n",
            DependencyListCodec.render(
                listOf(
                    DeclaredDependency("a:b:1", DependencyScope.IMPLEMENTATION, "a.jar"),
                    DeclaredDependency("a:b:1", DependencyScope.API, "b.jar"),
                    DeclaredDependency("a:b:1", DependencyScope.IMPLEMENTATION, "a.jar"),
                ),
            ),
        )
    }
}
