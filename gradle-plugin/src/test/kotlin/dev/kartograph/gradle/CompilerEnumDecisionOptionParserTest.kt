package dev.kartograph.gradle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CompilerEnumDecisionOptionParserTest {
    private val required = "root=file:///project output=file:///build/enum.tsv token=file:///build/token"

    @Test fun `Java enum opt in is independent and token marker mirrors actual options`() {
        fun enumEnabled(options: String, evidence: Boolean = true) =
            CompilerCallPositionOptionParser.enumDecisionsEnabled("javac",
                listOf("-Xplugin:KartographEvidence $required $options"), evidence)
        assertFalse(enumEnabled(""))
        assertFalse(enumEnabled("enumDecisions=false"))
        assertFalse(enumEnabled("callPositions=true"))
        assertTrue(enumEnabled("enumDecisions=true"))
        assertTrue(enumEnabled("callPositions=true enumDecisions=true"))
        assertTrue(enumEnabled("enumDecisions=true callPositions=false"))
        assertFalse(enumEnabled("enumDecisions=true", evidence = false))
        assertFalse(CompilerCallPositionOptionParser.enabled("javac",
            listOf("-Xplugin:KartographEvidence $required enumDecisions=true"), true))
        assertTrue(CompilerCallPositionOptionParser.enabled("javac",
            listOf("-Xplugin:KartographEvidence $required enumDecisions=true callPositions=true"), true))
        assertFalse(CompilerCallPositionOptionParser.enumDecisionsEnabled("javac",
            listOf("-Xplugin:Other enumDecisions=true", "src/enumDecisions=true.java"), true))
    }

    @Test fun `Java enum malformed duplicates and non constants collectors reject`() {
        for (options in listOf("enumDecisions=True", "enumDecisions=1", "enumDecisions=",
            "enumDecisions=true enumDecisions=true", "enumDecisions=false enumDecisions=true",
            "enumDecisions=true enumDecisions=false", "enumDecisions=false enumDecisions=false",
            "collector=dagger-bindings enumDecisions=true", "collector=kotlin-constants enumDecisions=true")) {
            assertFailsWith<IllegalArgumentException>(options) {
                CompilerCallPositionOptionParser.enumDecisionsEnabled("javac",
                    listOf("-Xplugin:KartographEvidence $required $options"), true)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            CompilerCallPositionOptionParser.enumDecisionsEnabled("javac", listOf(
                "-Xplugin:KartographEvidence $required enumDecisions=true",
                "-Xplugin:KartographEvidence $required enumDecisions=false"), true)
        }
    }

    @Test fun `Kotlin separate combined and comma option forms agree`() {
        val enum = "plugin:kartograph.compiler-evidence:enumDecisions"
        val calls = "plugin:kartograph.compiler-evidence:callPositions=true"
        for (arguments in listOf(listOf("-P", "$enum=true"), listOf("-P$enum=true"),
            listOf("-P", "$enum=true,$calls"), listOf("-P", calls, "-P$enum=true"))) {
            assertTrue(CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", arguments, true))
            assertFalse(CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", arguments, false))
        }
        assertFalse(CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", emptyList(), true))
        assertFalse(CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", listOf("-P$enum=false"), true))
        assertFalse(CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", listOf("-P", calls), true))
        assertFalse(CompilerCallPositionOptionParser.enabled("kotlin", listOf("-P$enum=true"), true))
        assertTrue(CompilerCallPositionOptionParser.enabled("kotlin", listOf("-P$enum=true", "-P", calls), true))
        assertFalse(CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", listOf(
            "-P", "plugin:other:enumDecisions=true", "-P", "plugin:kartograph.compiler-evidence:root=/enumDecisions=true"), true))
    }

    @Test fun `Kotlin malformed and repeated enum options reject across forms`() {
        val key = "plugin:kartograph.compiler-evidence:enumDecisions"
        for (arguments in listOf(listOf("-P", key), listOf("-P$key=True"), listOf("-P$key=1"),
            listOf("-P", "$key=true", "-P", "$key=true"), listOf("-P$key=true", "-P$key=false"),
            listOf("-P", "$key=false,$key=true"), listOf("-P", "$key=false", "-P$key=false"))) {
            assertFailsWith<IllegalArgumentException>(arguments.joinToString(" ")) {
                CompilerCallPositionOptionParser.enumDecisionsEnabled("kotlin", arguments, true)
            }
        }
    }
}
