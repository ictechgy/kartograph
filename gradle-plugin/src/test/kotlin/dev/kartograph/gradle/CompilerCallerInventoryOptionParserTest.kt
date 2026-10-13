package dev.kartograph.gradle

import kotlin.test.*

class CompilerCallerInventoryOptionParserTest {
    private val javac = "-Xplugin:KartographEvidence root=/project output=/evidence token=/token authoredCalls=true callerInventory=true classOutput=file:/project/classes"
    @Test fun `inventory is an independent explicit option with an exact task destination`() {
        assertTrue(CompilerCallPositionOptionParser.inventoryEnabled("javac", listOf(javac), true))
        assertTrue(CompilerCallPositionOptionParser.authoredEnabled("javac", listOf(javac), true))
        assertFalse(CompilerCallPositionOptionParser.enabled("javac", listOf(javac), true))
        assertFalse(CompilerCallPositionOptionParser.inventoryEnabled("javac", listOf(javac), false))
        assertEquals(java.io.File("/project/classes").canonicalFile, CompilerCallPositionOptionParser.inventoryOutput("javac", listOf(javac)))
        val kotlin = listOf("-P", "plugin:kartograph.compiler-evidence:callerInventory=true", "-Pplugin:kartograph.compiler-evidence:classOutput=file:/project/classes")
        assertTrue(CompilerCallPositionOptionParser.inventoryEnabled("kotlin", kotlin, true))
        assertEquals(java.io.File("/project/classes").canonicalFile, CompilerCallPositionOptionParser.inventoryOutput("kotlin", kotlin))
    }
    @Test fun `duplicate malformed and missing inventory options fail closed`() {
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.inventoryEnabled("javac", listOf("$javac callerInventory=true"), true) }
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.inventoryEnabled("javac", listOf(javac.replace("callerInventory=true", "callerInventory=yes")), true) }
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.inventoryEnabled("javac", listOf("$javac classOutput=/other"), true) }
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.inventoryOutput("kotlin", emptyList()) }
        assertFailsWith<IllegalArgumentException> { CompilerCallPositionOptionParser.inventoryEnabled("kotlin", listOf("-P", "plugin:kartograph.compiler-evidence:callerInventory=true,plugin:kartograph.compiler-evidence:callerInventory=false"), true) }
    }
}
