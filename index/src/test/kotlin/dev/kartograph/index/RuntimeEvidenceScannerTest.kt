package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class RuntimeEvidenceScannerTest {
    @Test
    fun `class lists accept comments dotted slash and class suffixes`(@TempDir root: Path) {
        val classList = root.resolve("classes.txt")
        classList.writeText(
            """
            # loaded during the smoke run
            com.example.Foo
            com/example/Bar.class
            com.example.Outer${'$'}Inner
            com.example.Foo
            """.trimIndent() + "\n",
        )

        assertEquals(
            setOf("com/example/Foo", "com/example/Bar", "com/example/Outer${'$'}Inner"),
            RuntimeEvidenceScanner().scan(listOf(classList), emptyList()),
        )
    }

    @Test
    fun `coverage reports mark classes with any covered counter inside the class`(@TempDir root: Path) {
        val report = root.resolve("report.xml")
        report.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="sample">
              <package name="com/example">
                <class name="com/example/Foo" sourcefilename="Foo.kt">
                  <method name="bar" desc="()V" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="4"/>
                    <counter type="LINE" missed="0" covered="2"/>
                  </method>
                  <counter type="LINE" missed="0" covered="2"/>
                </class>
                <class name="com/example/Bar" sourcefilename="Bar.kt">
                  <counter type="INSTRUCTION" missed="7" covered="0"/>
                  <counter type="LINE" missed="3" covered="0"/>
                </class>
              </package>
              <counter type="LINE" missed="3" covered="2"/>
            </report>
            """.trimIndent() + "\n",
        )

        assertEquals(setOf("com/example/Foo"), RuntimeEvidenceScanner().scan(emptyList(), listOf(report)))
    }

    @Test
    fun `coverage reports fail closed on a foreign root malformed counters and entities`(@TempDir root: Path) {
        val foreign = root.resolve("foreign.xml")
        foreign.writeText("<coverage><class name=\"com/example/Foo\"/></coverage>")
        val foreignError = assertFailsWith<RuntimeEvidenceScanningException> {
            RuntimeEvidenceScanner().scan(emptyList(), listOf(foreign))
        }
        assertContains(foreignError.message.orEmpty(), "not a JaCoCo/Kover XML report")

        val missingCovered = root.resolve("missing-covered.xml")
        missingCovered.writeText("<report><class name=\"com/example/Foo\"><counter type=\"LINE\"/></class></report>")
        assertContains(
            assertFailsWith<RuntimeEvidenceScanningException> {
                RuntimeEvidenceScanner().scan(emptyList(), listOf(missingCovered))
            }.message.orEmpty(),
            "counter without a covered count",
        )

        val badCovered = root.resolve("bad-covered.xml")
        badCovered.writeText("<report><class name=\"com/example/Foo\"><counter type=\"LINE\" covered=\"many\"/></class></report>")
        assertContains(
            assertFailsWith<RuntimeEvidenceScanningException> {
                RuntimeEvidenceScanner().scan(emptyList(), listOf(badCovered))
            }.message.orEmpty(),
            "non-numeric covered count",
        )

        val broken = root.resolve("broken.xml")
        broken.writeText("<report><class name=\"com/example/Foo\">")
        val brokenError = assertFailsWith<RuntimeEvidenceScanningException> {
            RuntimeEvidenceScanner().scan(emptyList(), listOf(broken))
        }
        assertContains(brokenError.message.orEmpty(), "invalid or uses a prohibited external entity")
        assertFalse(brokenError.message.orEmpty().contains(root.toString()))

        val empty = root.resolve("empty.xml")
        empty.writeText("")
        val emptyError = assertFailsWith<RuntimeEvidenceScanningException> {
            RuntimeEvidenceScanner().scan(emptyList(), listOf(empty))
        }
        assertContains(emptyError.message.orEmpty(), "invalid or uses a prohibited external entity")
        assertFalse(emptyError.message.orEmpty().contains(root.toString()))
    }

    @Test
    fun `class lists fail closed on malformed names and missing files`(@TempDir root: Path) {
        val malformed = root.resolve("malformed.txt")
        malformed.writeText("com.example.Foo\ncom.example Bad\n")
        val error = assertFailsWith<RuntimeEvidenceScanningException> {
            RuntimeEvidenceScanner().scan(listOf(malformed), emptyList())
        }
        assertContains(error.message.orEmpty(), "invalid class name at line 2")
        assertFalse(error.message.orEmpty().contains(root.toString()))

        assertContains(
            assertFailsWith<RuntimeEvidenceScanningException> {
                RuntimeEvidenceScanner().scan(listOf(root.resolve("missing.txt")), emptyList())
            }.message.orEmpty(),
            "does not exist",
        )
        assertContains(
            assertFailsWith<RuntimeEvidenceScanningException> {
                RuntimeEvidenceScanner().scan(emptyList(), listOf(root.resolve("missing.xml")))
            }.message.orEmpty(),
            "does not exist",
        )
    }
}
