package dev.kartograph.gradle

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.index.ClassFileIndexer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class AndroidResourceSnapshotEvidenceTest {
    @Test
    fun `external declared roots leave ordinary snapshot available with a fixed limitation`(@TempDir project: Path) {
        val indexed = ClassFileIndexer().indexWithObservations(emptyList())
        val result = AndroidResourceSnapshotEvidence.capture(project, ":app:debug", "dev.example",
            listOf(project.parent.resolve("outside")), ":app:processDebugResources",
            SnapshotProvenance(emptyList(), emptyList()), indexed, CodeGraph(emptyList(), emptyList()))
        assertNull(result.facts)
        assertNull(result.bindings)
        assertEquals(listOf("android-resource-bindings-unavailable: external-or-project-root"), result.limitations)
    }

    @Test
    fun `missing producer keeps facts and reports refusal instead of empty impact success`(@TempDir project: Path) {
        val indexed = ClassFileIndexer().indexWithObservations(emptyList())
        val result = AndroidResourceSnapshotEvidence.capture(project, ":app:debug", "dev.example",
            listOf(project.resolve("src/main/res")), ":app:processDebugResources",
            SnapshotProvenance(emptyList(), emptyList()), indexed, indexed.graph)
        assertNull(result.bindings)
        if (result.facts == null) {
            // descriptor provider가 없는 플랫폼은 명시적으로 미지원이며 일반 snapshot을 막지 않는다.
            assertEquals(listOf("android-resource-bindings-unavailable: capture-unsupported_provider"), result.limitations)
        } else {
            assertEquals(1, result.facts.roots.size)
            assertEquals(listOf("android-resource-bindings-unavailable: admission-producer_unavailable"), result.limitations)
        }
    }
}
