package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class McpCommandTest {
    @Test
    fun `mcp command advertises pinned legacy stdio and requires an explicit snapshot`() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(arrayOf("mcp", "--help"), PrintStream(output), PrintStream(error)))
        assertContains(output.toString(), "2025-11-25")
        assertContains(output.toString(), "--graph-file")
        assertEquals(64, KartographCli.run(arrayOf("mcp"), PrintStream(output), PrintStream(error)))
    }
}
