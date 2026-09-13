package dev.kartograph.gradle

import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.QuerySnapshotCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class JvmSnapshotRetentionIntegrationTest {
    @Test
    fun `baseline state and recursive keep changes remain in the captured graph`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'snapshot-retention'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph {
                snapshotsEnabled = true
                includeSourcePaths = true
                baseline = file('baseline.json')
                keepRules.from('keep.pro')
            }
        """.trimIndent())
        val sources = Files.createDirectories(root.resolve("src/main/java/p"))
        Files.writeString(sources.resolve("Entry.java"), "package p; public class Entry {}")
        Files.writeString(sources.resolve("Kept.java"), "package p; public class Kept {}")
        Files.writeString(root.resolve("baseline.json"), BaselineCodec.render(listOf(
            Finding(NodeId("class:p/Entry"), SourceLocation("Entry.java", 1)),
        )))
        Files.writeString(root.resolve("keep.pro"), "-include nested.pro\n")
        Files.writeString(root.resolve("nested.pro"), "-keep class p.Kept\n")
        fun build() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace").build()
        fun snapshot() = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        build()
        val first = snapshot()
        assertTrue(NodeId("class:p/Entry") in first.graph.nodes)
        assertEquals(setOf(NodeId("class:p/Entry")), first.suppressed)
        assertTrue(first.retention.any { it.nodeId == NodeId("class:p/Kept") })
        assertTrue(first.provenance!!.inputs.any { it.role == "baseline" && it.path == "baseline.json" })
        assertTrue(first.provenance!!.inputs.any { it.role == "keepRules" && it.path == "nested.pro" })

        Files.writeString(root.resolve("nested.pro"), "-keep class p.Entry\n")
        val changed = build()
        assertTrue(changed.output.contains("Reusing configuration cache"), changed.output)
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":compileJava")!!.outcome)
        val second = snapshot()
        assertTrue(second.retention.any { it.nodeId == NodeId("class:p/Entry") })
        assertFalse(second.retention.any { it.nodeId == NodeId("class:p/Kept") })
        assertEquals(first.suppressed, second.suppressed)
        assertEquals(first.graph.nodes, second.graph.nodes)
    }
}
