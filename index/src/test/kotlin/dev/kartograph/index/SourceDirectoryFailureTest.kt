package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class SourceDirectoryFailureTest {
    @Test fun `unreadable directory preserves sibling mappings and excludes build before opening`(@TempDir root: Path) {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "requires POSIX permissions")
        val source = root.resolve("src/Example.java").also { it.parent.createDirectories(); it.writeText("class Example {}") }
        val locked = root.resolve("locked").createDirectories()
        val build = root.resolve("build").createDirectories()
        val permissions = Files.getPosixFilePermissions(locked)
        try {
            Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
            Files.setPosixFilePermissions(build, emptySet<PosixFilePermission>())
            assumeTrue(!Files.isReadable(locked), "must execute as a non-privileged POSIX user")
            val node = GraphNode(NodeId("class:Example"), "Example", NodeKind.CLASS, location = SourceLocation("Example.java"))
            val result = SourcePathIndex.resolve(CodeGraph(listOf(node), emptyList()), root)
            assertEquals("src/Example.java", result.byNodeId[node.id])
            assertTrue(result.limitations.any { it.startsWith("unreadable-source-directories: 1 ") })
            assertFalse(result.limitations.any { it.startsWith("unavailable-source-paths:") })
        } finally {
            Files.setPosixFilePermissions(locked, permissions)
            Files.setPosixFilePermissions(build, permissions)
        }
    }
}
