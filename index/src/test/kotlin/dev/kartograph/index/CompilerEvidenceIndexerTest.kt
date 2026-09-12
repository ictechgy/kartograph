package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import org.junit.jupiter.api.io.TempDir

class CompilerEvidenceIndexerTest {
    @Test
    fun `an embedded resource cannot opt the caller into compiler evidence`(@TempDir root: Path) {
        val resource = root.resolve("META-INF/kartograph/compiler-references.tsv")
        Files.createDirectories(resource.parent)
        Files.writeString(resource, "untrusted resource")
        val graph = CodeGraph(emptyList(), emptyList())
        assertEquals(graph, CompilerEvidenceIndexer.enrich(IndexedClasses(graph, emptyList()), listOf(root), emptyList()).graph)
    }

    @Test
    fun `explicit raw facts without a completed compiler receipt are rejected`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("evidence.tsv"), "untrusted raw facts")
        val failure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrich(IndexedClasses(CodeGraph(emptyList(), emptyList()), emptyList()), listOf(root), listOf(file))
        }
        assertContains(failure.message.orEmpty(), "completed compiler receipt")
    }
}
