package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.*

class OutputFailuresTest {
    @Test fun `swallowed write failures distinguish closed consumer from storage failure`() {
        for ((message, expected) in listOf("Broken pipe" to "consumer closed the pipe", "파이프가 닫히는 중입니다" to "consumer closed the pipe", "No space left on device" to "insufficient space",
            "Access denied" to "permissions, capacity, and availability")) {
            val tracker = OutputFailureTracker(object : OutputStream() { override fun write(value: Int) { throw IOException(message) } })
            val output = TrackedPrintStream(tracker)
            output.print("payload")
            assertTrue(output.checkError())
            assertContains(outputFailureMessage(output), expected)
            assertEquals(message, tracker.failure?.message)
        }
        val bytes = ByteArrayOutputStream()
        val output = TrackedPrintStream(OutputFailureTracker(bytes))
        output.print("정상")
        output.flush()
        assertFalse(output.checkError())
        assertEquals("정상", bytes.toString(Charsets.UTF_8))
    }

    @Test fun `flush error preserves first failure and unknown PrintStream remains generic`() {
        val tracker = OutputFailureTracker(object : OutputStream() {
            override fun write(value: Int) { throw IOException("Broken pipe") }
            override fun flush() { throw IOException("later failure") }
        })
        val output = TrackedPrintStream(tracker)
        output.print("x")
        output.flush()
        assertContains(outputFailureMessage(output), "consumer closed")
        assertContains(outputFailureMessage(java.io.PrintStream(ByteArrayOutputStream())), "output destination")
    }
}
