package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class WitnessRejectionTest {
    @Test
    fun `only known validation text is retained from a nested failure`() {
        val known = IllegalArgumentException("wrapper with private context",
            IllegalArgumentException("unsupported javac option for compiler witness"))
        assertEquals(WitnessRejection.UNSUPPORTED_JAVAC, WitnessRejection.from(known))
        assertEquals(WitnessRejection.UNAVAILABLE,
            WitnessRejection.from(IllegalArgumentException("untrusted opaque diagnostic /private/fixture")))
    }

    @Test
    fun `rejection documents cannot supply arbitrary output or follow links`(@TempDir root: Path) {
        val file = root.resolve("rejection.txt")
        Files.writeString(file, WitnessRejection.MISSING_INPUT.name)
        assertEquals(WitnessRejection.MISSING_INPUT.description, WitnessRejection.describe(listOf(file.toFile())))
        Files.writeString(file, "untrusted opaque diagnostic")
        assertEquals(WitnessRejection.UNAVAILABLE.description, WitnessRejection.describe(listOf(file.toFile())))
        Files.writeString(file, "x".repeat(1024))
        assertEquals(WitnessRejection.UNAVAILABLE.description, WitnessRejection.describe(listOf(file.toFile())))
        val link = root.resolve("linked.txt")
        Files.createSymbolicLink(link, file)
        assertEquals(WitnessRejection.UNAVAILABLE.description, WitnessRejection.describe(listOf(link.toFile())))
        assertEquals(WitnessRejection.UNAVAILABLE.description, WitnessRejection.describe(emptyList()))
    }
}
