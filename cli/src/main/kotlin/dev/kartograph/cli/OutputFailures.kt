package dev.kartograph.cli

import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/** PrintStream이 삼킨 예외를 보존하여 닫힌 pipe와 저장 장치 실패를 구분한다. */
internal class OutputFailureTracker(private val destination: OutputStream) : OutputStream() {
    var failure: IOException? = null
        private set

    override fun write(value: Int) = record { destination.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) = record { destination.write(bytes, offset, length) }
    override fun flush() = record { destination.flush() }

    private inline fun record(action: () -> Unit) {
        try { action() } catch (problem: IOException) {
            if (failure == null) failure = problem
            throw problem
        }
    }

    fun diagnostic(): String {
        val message = failure?.message.orEmpty().lowercase(java.util.Locale.ROOT)
        return when {
            message == "broken pipe" || message.contains("pipe is being closed") || message.contains("pipe has been ended") ||
                message.trim() == "파이프가 닫히는 중입니다" ->
                "output consumer closed the pipe before the complete output was written"
            message.contains("no space left on device") || message.contains("not enough space on the disk") || message.contains("disk full") ->
                "output destination has insufficient space; the output is incomplete"
            else -> "output could not be written completely; check destination permissions, capacity, and availability"
        }
    }
}

internal class TrackedPrintStream(val tracker: OutputFailureTracker) :
    PrintStream(tracker, false, StandardCharsets.UTF_8)

internal fun outputFailureMessage(output: PrintStream): String =
    (output as? TrackedPrintStream)?.tracker?.diagnostic()
        ?: "output could not be written completely; check the output destination"
