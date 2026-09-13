package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.io.TempDir

class BuildLogicInputsTest {
    @Test
    fun `nested build logic changes are watched but build outputs are not`(@TempDir root: Path) {
        val logic = root.resolve("build-logic")
        fun fingerprint() = ContentFingerprint.capture(root, logic, "build-logic-watch", "logic")
        val absent = fingerprint()
        val output = Files.createDirectories(logic.resolve("convention/build/classes"))
        Files.writeString(output.resolve("Generated.class"), "generated output")
        assertEquals(absent, fingerprint())
        val source = Files.createDirectories(logic.resolve("convention/src/main/kotlin/build"))
        val file = source.resolve("Convention.kt")
        Files.writeString(file, "class Convention")
        val first = fingerprint()
        assertNotEquals(absent.sha256, first.sha256)
        Files.writeString(file, "class Convention { val flag = true }")
        assertNotEquals(first.sha256, fingerprint().sha256)
        val beforeOutput = fingerprint()
        Files.writeString(output.resolve("Generated.class"), "changed output")
        assertEquals(beforeOutput, fingerprint())
        Files.writeString(logic.resolve("convention/build.gradle.kts"), "plugins {}")
        assertNotEquals(beforeOutput.sha256, fingerprint().sha256)
        Files.createSymbolicLink(source.resolve("Linked.kt"), file)
        assertFailsWith<IllegalArgumentException> { fingerprint() }
    }
}
