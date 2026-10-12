package dev.kartograph.gradle

import kotlin.test.*

class CompilerAuthoredCallOptionParserTest {
    @Test fun `authored opt in is independent from the v3 marker`() {
        val java = listOf("-Xplugin:KartographEvidence root=x output=y token=z authoredCalls=true")
        assertTrue(CompilerCallPositionOptionParser.authoredEnabled("javac", java, true))
        assertFalse(CompilerCallPositionOptionParser.enabled("javac", java, true))
        assertFalse(CompilerCallPositionOptionParser.authoredEnabled("javac", java, false))
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.authoredEnabled("javac",
            listOf(java.single() + " authoredCalls=false"), true) }
        val kotlin = listOf("-P", "plugin:kartograph.compiler-evidence:authoredCalls=true")
        assertTrue(CompilerCallPositionOptionParser.authoredEnabled("kotlin", kotlin, true))
        assertFalse(CompilerCallPositionOptionParser.enabled("kotlin", kotlin, true))
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.authoredEnabled("kotlin", kotlin + kotlin, true) }
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.authoredEnabled("javac",
            listOf("-Xplugin:KartographEvidence root=x output=y token=z authoredCalls=yes"), true) }
    }
}
