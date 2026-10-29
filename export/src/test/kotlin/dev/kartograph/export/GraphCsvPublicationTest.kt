package dev.kartograph.export

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class GraphCsvPublicationTest {
    @Test fun `unexpected temporary files are cleaned without masking the primary write failure`(@TempDir root: Path) {
        val failure = IOException("original write failed")
        val thrown = assertFailsWith<IOException> { GraphCsvPublication.publish(root.resolve("graph")) { staging ->
            val directory = Files.createDirectory(staging.resolve("temporary"))
            Files.writeString(directory.resolve("partial.data"), "temporary")
            throw failure
        } }
        assertSame(failure, thrown)
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test fun `cooperating publishers reserve destination and preserve a late arriving empty directory`(@TempDir root: Path) {
        val target = root.resolve("graph")
        assertFailsWith<IOException> { GraphCsvPublication.publish(target) { staging ->
            assertFailsWith<IOException> { GraphCsvPublication.publish(target) { error("reservation must prevent second writer") } }
            Files.writeString(staging.resolve("nodes.csv"), "complete")
            Files.createDirectory(target)
        } }
        assertTrue(Files.isDirectory(target))
        assertEquals(0L, Files.list(target).use { it.count() })
        assertEquals(1L, Files.list(root).use { it.count() })
    }

    @Test fun `failure leaves no published output and retry can publish a complete set`(@TempDir root: Path) {
        val target = root.resolve("graph")
        assertFailsWith<IOException> { GraphCsvPublication.publish(target) {
            Files.writeString(it.resolve("nodes.csv"), "partial")
            throw IOException("synthetic write failure")
        } }
        assertFalse(Files.exists(target))
        assertEquals(0L, Files.list(root).use { it.count() })
        GraphCsvPublication.publish(target) {
            Files.writeString(it.resolve("nodes.csv"), "complete")
            Files.writeString(it.resolve("manifest.json"), "complete manifest")
        }
        assertEquals("complete", Files.readString(target.resolve("nodes.csv")))
        assertFailsWith<IOException> { GraphCsvPublication.publish(target) { error("must not write") } }
        assertEquals("complete", Files.readString(target.resolve("nodes.csv")))
    }
}
