package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BridgeTimestampTest {
    @Test
    fun `all transports separate archive mtime from extraction time`(@TempDir project: Path) {
        val source = project.resolve("Plugin.kt")
        source.writeText("class Plugin")
        Files.setLastModifiedTime(source, FileTime.from(Instant.parse("1985-10-26T08:15:00Z")))
        val scanner = BridgeFactScanner(project)
        val before = Instant.now().toEpochMilli()
        val documents = listOf(scanner.scan(), scanner.scanMessages(), scanner.scanEvents(), scanner.scanReactNativeEvents())
        val after = Instant.now().toEpochMilli()
        for (document in documents) {
            assertTrue(Instant.parse(document.generatedAt).toEpochMilli() in before..after)
            assertEquals("1985-10-26T08:15:00.000Z", document.sourceModifiedAt)
            assertTrue(Regex(".*\\.\\d{3}Z").matches(document.generatedAt))
        }
        Files.delete(source)
        for (document in listOf(scanner.scan(), scanner.scanMessages(), scanner.scanEvents(), scanner.scanReactNativeEvents())) {
            assertNull(document.sourceModifiedAt)
            assertTrue(Instant.parse(document.generatedAt).isAfter(Instant.EPOCH))
        }
    }

    @Test
    fun `explicit extraction clock is normalized for reproducible fixtures`(@TempDir project: Path) {
        val scanner = BridgeFactScanner(project)
        val clock = "2026-09-20T12:00:00.123456+09:00"
        for (document in listOf(scanner.scan(clock), scanner.scanMessages(clock), scanner.scanEvents(clock), scanner.scanReactNativeEvents(clock))) {
            assertEquals("2026-09-20T03:00:00.123Z", document.generatedAt)
        }
    }
}
